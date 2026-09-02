package io.outcomeprobe.execution;

import io.outcomeprobe.foundation.CanonicalJson;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.foundation.SnapshotSetHash;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.profile.TargetProfileSpecMapper;
import io.outcomeprobe.profile.VersionedTargetProfile;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactSecretPolicy;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.core.StreamReadFeature;

/**
 * Run Artifact snapshot을 생성하고 JSONB round-trip 뒤 무결성을 검증한다.
 *
 * <p>source hash는 import 원본 변조를, canonical hash는 compiled_plan 내부 snapshot
 * 변조를 검출한다. 검증 실패 시 현재 Artifact를 다시 조회해 보정하지 않고 명시적으로
 * {@link ProblemCode#ARTIFACT_SNAPSHOT_CORRUPTED}를 보고한다.</p>
 */
@Component
public class RunSnapshotIntegrity {

    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final TargetProfileSpecMapper profileSpecMapper;

    /**
     * 단위 테스트와 기존 직접 생성 호출을 위한 호환 생성자다.
     *
     * @param objectMapper JSON mapper
     */
    public RunSnapshotIntegrity(ObjectMapper objectMapper) {
        this(objectMapper, new TargetProfileSpecMapper());
    }

    /**
     * Snapshot canonicalization mapper와 Profile 의미 매퍼를 주입한다.
     *
     * <p>Spring은 이 생성자를 명시적으로 선택한다. 따라서 Artifact 조회와 Run 시작
     * snapshot 경계가 같은 {@link TargetProfileSpecMapper} bean을 공유한다.</p>
     *
     * @param objectMapper JSON mapper
     * @param profileSpecMapper Profile spec을 실행 모델로 바꾸는 공용 매퍼
     */
    @Autowired
    public RunSnapshotIntegrity(ObjectMapper objectMapper,
                                TargetProfileSpecMapper profileSpecMapper) {
        this.objectMapper = objectMapper;
        this.profileSpecMapper = profileSpecMapper;
        this.yamlMapper = new ObjectMapper(YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    /**
     * 저장된 Artifact 원본 hash를 검증하고 실행용 safe spec snapshot을 만든다.
     *
     * @param definition 저장된 Artifact
     * @param expectedKind 예상 Artifact 종류
     * @return immutable Artifact snapshot
     */
    public ArtifactSnapshot snapshot(ArtifactDefinition definition, ArtifactKind expectedKind) {
        if (definition == null || definition.kind() != expectedKind
                || definition.metadata() == null || definition.contentHash() == null
                || definition.parsedJson() == null) {
            throw corrupted("artifact.kind", "Snapshot 대상 Artifact 종류가 일치하지 않습니다.");
        }
        ArtifactSecretPolicy.validateSafeSpec(definition.parsedJson());
        ArtifactSecretPolicy.validateRawYamlText(definition.rawYaml());
        verifySourceHash(definition.rawYaml(), definition.contentHash().hexValue(),
                expectedKind.name().toLowerCase(java.util.Locale.ROOT));
        String safeRawYaml = createSafeRawYaml(definition);
        String safeContentHash = HashValue.sha256(safeRawYaml).hexValue();
        String canonicalHash = artifactCanonicalHash(definition.kind(), definition.apiVersion(),
                definition.metadata().name(), definition.metadata().version(),
                definition.contentHash().hexValue(), safeContentHash,
                safeRawYaml, definition.parsedJson());
        return new ArtifactSnapshot(
                definition.kind(),
                definition.apiVersion(),
                definition.metadata().name(),
                definition.metadata().version(),
                definition.contentHash().hexValue(),
                safeContentHash,
                canonicalHash,
                safeRawYaml,
                definition.parsedJson());
    }

    /**
     * 선택된 versioned Profile의 원본 hash를 검증하고 secret-free snapshot을 만든다.
     *
     * @param definition Run 생성 시 선택된 Profile
     * @return immutable Target Profile snapshot
     */
    public TargetProfileSafeSnapshot snapshot(VersionedTargetProfile definition) {
        try {
            if (definition == null || definition.profile() == null
                    || definition.parsedSpec() == null) {
                throw corrupted("targetProfile", "Target Profile snapshot 원본이 없습니다.");
            }
            ArtifactSecretPolicy.validateSafeSpec(definition.parsedSpec());
            ArtifactSecretPolicy.validateSafeDefaultHeaders(definition.profile().defaultHeaders());
            ArtifactSecretPolicy.validateRawYamlText(definition.rawYaml());
            verifySourceHash(definition.rawYaml(), definition.sourceContentHash(), "targetProfile");
            verifyProfileSourceBinding(definition);
            String canonicalHash = TargetProfileSafeSnapshot.calculateCanonicalHash(objectMapper,
                    definition.apiVersion(), definition.profile().name(), definition.version(),
                    definition.sourceContentHash(), definition.profile());
            return new TargetProfileSafeSnapshot(
                    definition.apiVersion(),
                    definition.profile().name(),
                    definition.version(),
                    definition.sourceContentHash(),
                    canonicalHash,
                    definition.profile());
        } catch (ArtifactImportException exception) {
            if (exception.problemCode() == ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED) {
                throw exception;
            }
            throw corrupted("targetProfile", exception.getMessage());
        } catch (RuntimeException exception) {
            throw corrupted("targetProfile", "Target Profile snapshot 의미를 검증할 수 없습니다.");
        }
    }

    /**
     * 실행 직전 v2 plan의 identity와 canonical hash를 모두 검증한다.
     *
     * @param plan 검증할 Run 계획
     */
    public void verify(CompiledRunPlan plan) {
        if (plan == null || plan.planSchemaVersion() != CompiledRunPlan.CURRENT_SCHEMA_VERSION
                || !plan.hasCompleteSnapshots()) {
            throw corrupted("compiledPlan", "v2 Artifact snapshot이 없는 legacy Run 계획입니다.");
        }
        verifyArtifactSnapshot(plan.scenarioSnapshot(), ArtifactKind.SCENARIO);
        verifyArtifactSnapshot(plan.contractSnapshot(), ArtifactKind.PROVIDER_CONTRACT);
        verifyProfileSnapshot(plan.targetProfileSnapshot());
        String actualSetHash = snapshotSetHash(plan.planSchemaVersion(), plan.scenarioSnapshot(),
                plan.contractSnapshot(), plan.targetProfileSnapshot());
        if (!actualSetHash.equals(plan.snapshotSetHash())) {
            throw corrupted("snapshotSetHash", "Artifact snapshot set hash가 일치하지 않습니다.");
        }
        if (!Objects.equals(plan.scenarioName(), plan.scenarioSnapshot().name())
                || !Objects.equals(plan.scenarioVersion(), plan.scenarioSnapshot().version())
                || !Objects.equals(plan.scenarioHash(),
                        plan.scenarioSnapshot().sourceContentHash())) {
            throw corrupted("scenarioSnapshot", "Scenario flat metadata와 snapshot이 일치하지 않습니다.");
        }
        if (!Objects.equals(plan.contractName(), plan.contractSnapshot().name())
                || !Objects.equals(plan.contractVersion(), plan.contractSnapshot().version())) {
            throw corrupted("contractSnapshot", "Contract flat metadata와 snapshot이 일치하지 않습니다.");
        }
        tools.jackson.databind.JsonNode contractRef = plan.scenarioSnapshot().safeSpec().path("contractRef");
        if (!Objects.equals(plan.contractSnapshot().name(), contractRef.path("name").asText(null))
                || !Objects.equals(plan.contractSnapshot().version(),
                        contractRef.path("version").asText(null))) {
            throw corrupted("scenarioSnapshot.contractRef",
                    "Scenario contractRef와 Contract snapshot identity가 일치하지 않습니다.");
        }
        if (!Objects.equals(plan.targetProfileName(), plan.targetProfileSnapshot().name())) {
            throw corrupted("targetProfileSnapshot", "Profile flat metadata와 snapshot이 일치하지 않습니다.");
        }
    }

    /**
     * Callback request_snapshot에서 복원한 Profile snapshot hash를 검증한다.
     *
     * @param snapshot 검증할 Profile snapshot
     */
    public void verifyProfileSnapshot(TargetProfileSafeSnapshot snapshot) {
        if (snapshot == null || snapshot.profile() == null
                || !Objects.equals(snapshot.name(), snapshot.profile().name())) {
            throw corrupted("targetProfileSnapshot", "Target Profile snapshot 구조가 손상되었습니다.");
        }
        try {
            ArtifactSecretPolicy.validateSafeDefaultHeaders(snapshot.profile().defaultHeaders());
        } catch (ArtifactImportException exception) {
            throw corrupted("targetProfileSnapshot.defaultHeaders", exception.getMessage());
        }
        if (!snapshot.hasValidCanonicalHash(objectMapper)) {
            throw corrupted("targetProfileSnapshot.canonicalSnapshotHash",
                    "Target Profile snapshot hash가 일치하지 않습니다.");
        }
    }

    /**
     * 세 snapshot hash와 plan schema version을 묶은 상위 무결성 hash를 계산한다.
     *
     * @param planSchemaVersion compiled plan schema version
     * @param scenario Scenario snapshot
     * @param contract ProviderContract snapshot
     * @param profile TargetProfile snapshot
     * @return snapshot set SHA-256
     */
    public static String snapshotSetHash(int planSchemaVersion, ArtifactSnapshot scenario,
                                         ArtifactSnapshot contract,
                                         TargetProfileSafeSnapshot profile) {
        return SnapshotSetHash.calculate(planSchemaVersion,
                scenario.canonicalSnapshotHash(),
                contract.canonicalSnapshotHash(),
                profile.canonicalSnapshotHash());
    }

    /**
     * Scenario·Contract snapshot의 종류와 canonical hash를 검증한다.
     */
    private void verifyArtifactSnapshot(ArtifactSnapshot snapshot, ArtifactKind expectedKind) {
        if (snapshot == null || snapshot.kind() != expectedKind || snapshot.safeSpec() == null) {
            throw corrupted("artifactSnapshot", "Artifact snapshot 구조가 손상되었습니다.");
        }
        try {
            ArtifactSecretPolicy.validateSafeSpec(snapshot.safeSpec());
            verifySourceHash(snapshot.safeRawYaml(), snapshot.safeContentHash(),
                    "artifactSnapshot.safeRawYaml");
            verifySafeRawYaml(snapshot, expectedKind);
        } catch (ArtifactImportException exception) {
            throw corrupted("artifactSnapshot.safeSpec", exception.getMessage());
        }
        String actual = artifactCanonicalHash(snapshot.kind(), snapshot.apiVersion(), snapshot.name(),
                snapshot.version(), snapshot.sourceContentHash(), snapshot.safeContentHash(),
                snapshot.safeRawYaml(), snapshot.safeSpec());
        if (!actual.equals(snapshot.canonicalSnapshotHash())) {
            throw corrupted("artifactSnapshot.canonicalSnapshotHash",
                    "Artifact snapshot hash가 일치하지 않습니다: " + snapshot.name());
        }
    }

    /**
     * Artifact snapshot의 identity·안전 YAML·safe spec을 하나의 canonical hash로 묶는다.
     *
     * @param kind Artifact 종류
     * @param apiVersion DSL API 버전
     * @param name Artifact 이름
     * @param version Artifact SemVer
     * @param sourceContentHash import 원본 YAML source hash
     * @param safeContentHash comment-free safe YAML source hash
     * @param safeRawYaml 주석을 제거하고 credential 검사를 통과한 안전 YAML
     * @param safeSpec 실행 spec
     * @return snapshot canonical hash
     */
    private String artifactCanonicalHash(ArtifactKind kind, String apiVersion, String name,
                                         String version, String sourceContentHash,
                                         String safeContentHash, String safeRawYaml,
                                         tools.jackson.databind.JsonNode safeSpec) {
        ObjectNode material = objectMapper.createObjectNode();
        material.put("kind", kind.name());
        material.put("apiVersion", apiVersion);
        material.put("name", name);
        material.put("version", version);
        material.put("sourceContentHash", sourceContentHash);
        material.put("safeContentHash", safeContentHash);
        material.put("safeRawYaml", safeRawYaml);
        material.set("safeSpec", safeSpec);
        return CanonicalJson.sha256(objectMapper, material);
    }

    /** 원본 YAML을 안전한 문서 tree로 다시 직렬화해 주석과 비의미 원문을 제거한다. */
    private String createSafeRawYaml(ArtifactDefinition definition) {
        JsonNode root = parseYaml(definition.rawYaml(), "artifact.rawYaml");
        ArtifactSecretPolicy.validateSafeDocument(root);
        verifyDocumentBinding(root, definition.kind(), definition.apiVersion(),
                definition.metadata().name(), definition.metadata().version(),
                definition.parsedJson());
        return writeSafeYaml(root, "artifact.rawYaml");
    }

    /**
     * Profile 원본 YAML, import spec, 실행 모델이 한 가지 의미를 나타내는지 검증한다.
     *
     * <p>source hash만 맞춘 서로 다른 세 표현을 Run에 고정하는 공격을 막기 위해
     * 엄격하게 읽은 YAML envelope와 spec을 먼저 결속하고, 같은 공용 매퍼로 복원한
     * 실행 모델까지 record 동등성으로 확인한다.</p>
     */
    private void verifyProfileSourceBinding(VersionedTargetProfile definition) {
        JsonNode root = parseYaml(definition.rawYaml(), "targetProfile.rawYaml");
        ArtifactSecretPolicy.validateSafeDocument(root);
        JsonNode metadata = root.path("metadata");
        if (!Objects.equals(definition.apiVersion(), root.path("apiVersion").asText(null))
                || !Objects.equals("TargetProfile", root.path("kind").asText(null))
                || !Objects.equals(definition.profile().name(),
                        metadata.path("name").asText(null))
                || !Objects.equals(definition.version(),
                        metadata.path("version").asText(null))) {
            throw corrupted("targetProfile.rawYaml",
                    "Target Profile YAML envelope가 선택된 identity와 일치하지 않습니다.");
        }
        if (!Objects.equals(definition.parsedSpec(), root.get("spec"))) {
            throw corrupted("targetProfile.parsedSpec",
                    "Target Profile YAML spec과 import spec이 일치하지 않습니다.");
        }
        TargetProfile reconstructed = profileSpecMapper.toProfile(
                definition.profile().name(), definition.parsedSpec());
        if (!Objects.equals(reconstructed, definition.profile())) {
            throw corrupted("targetProfile.profile",
                    "Target Profile import spec과 실행 모델이 일치하지 않습니다.");
        }
    }

    /** 저장된 안전 YAML이 canonical 출력이고 snapshot identity·spec과 묶였는지 확인한다. */
    private void verifySafeRawYaml(ArtifactSnapshot snapshot, ArtifactKind expectedKind) {
        JsonNode root = parseYaml(snapshot.safeRawYaml(), "artifactSnapshot.safeRawYaml");
        ArtifactSecretPolicy.validateSafeDocument(root);
        verifyDocumentBinding(root, expectedKind, snapshot.apiVersion(), snapshot.name(),
                snapshot.version(), snapshot.safeSpec());
        String canonicalSafeYaml = writeSafeYaml(root, "artifactSnapshot.safeRawYaml");
        if (!Objects.equals(canonicalSafeYaml, snapshot.safeRawYaml())) {
            throw corrupted("artifactSnapshot.safeRawYaml",
                    "안전 YAML이 canonical snapshot 표현과 일치하지 않습니다.");
        }
    }

    /** YAML 문서의 identity와 spec이 별도 snapshot 필드와 정확히 같은지 확인한다. */
    private void verifyDocumentBinding(JsonNode root, ArtifactKind kind, String apiVersion,
                                       String name, String version, JsonNode safeSpec) {
        JsonNode metadata = root.path("metadata");
        if (!root.isObject()
                || !Objects.equals(apiVersion, root.path("apiVersion").asText(null))
                || !Objects.equals(kindText(kind), root.path("kind").asText(null))
                || !Objects.equals(name, metadata.path("name").asText(null))
                || !Objects.equals(version, metadata.path("version").asText(null))
                || !Objects.equals(safeSpec, root.get("spec"))) {
            throw corrupted("artifactSnapshot.safeRawYaml",
                    "안전 YAML의 identity 또는 spec이 snapshot 필드와 일치하지 않습니다.");
        }
    }

    /** Artifact enum을 DSL envelope의 kind 문자열로 변환한다. */
    private String kindText(ArtifactKind kind) {
        return switch (kind) {
            case PROVIDER_CONTRACT -> "ProviderContract";
            case SCENARIO -> "Scenario";
            case TARGET_PROFILE -> "TargetProfile";
            case SCENARIO_PACK -> "ScenarioPack";
        };
    }

    /** YAML 문자열을 안전하게 tree로 읽고 snapshot 손상 오류로 정규화한다. */
    private JsonNode parseYaml(String rawYaml, String field) {
        try {
            JsonNode root = yamlMapper.readTree(rawYaml);
            if (root == null || !root.isObject()) {
                throw corrupted(field, "Artifact YAML은 최상위 객체여야 합니다.");
            }
            return root;
        } catch (ArtifactImportException exception) {
            throw exception;
        } catch (Exception exception) {
            throw corrupted(field, "Artifact YAML을 안전하게 해석할 수 없습니다.");
        }
    }

    /** 문서 tree를 comment-free YAML로 직렬화하고 실패를 snapshot 손상으로 바꾼다. */
    private String writeSafeYaml(JsonNode root, String field) {
        try {
            return yamlMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw corrupted(field, "Artifact 안전 YAML을 직렬화할 수 없습니다.");
        }
    }

    /** 주어진 YAML 내용을 다시 해시해 경계에 저장된 content hash와 비교한다. */
    private void verifySourceHash(String rawYaml, String expectedHash, String field) {
        if (rawYaml == null || expectedHash == null
                || !HashValue.sha256(rawYaml).hexValue().equals(expectedHash)) {
            throw corrupted(field + ".sourceContentHash", "Artifact 원본 hash가 일치하지 않습니다.");
        }
    }

    /**
     * Snapshot 무결성 예외를 일관된 문제 코드로 만든다.
     */
    private ArtifactImportException corrupted(String field, String detail) {
        return new ArtifactImportException(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED, field, detail);
    }
}
