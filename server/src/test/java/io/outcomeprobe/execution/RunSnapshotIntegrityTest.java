package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.CanonicalJson;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.profile.TargetProfileSpecMapper;
import io.outcomeprobe.profile.VersionedTargetProfile;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Run v2 Artifact snapshot의 round-trip·hash·메모리 불변성을 검증한다.
 */
class RunSnapshotIntegrityTest {

    private static final String API_VERSION = "outcomeprobe.io/v1alpha1";
    private static final String PROFILE_YAML = """
            apiVersion: outcomeprobe.io/v1alpha1
            kind: TargetProfile
            metadata:
              name: test-target
              displayName: Snapshot Test Target
              version: 1.0.0
            spec:
              baseUri: http://localhost:8091
              allowedPaths:
                - /**
              allowedMethods:
                - GET
                - POST
              allowedHosts:
                - localhost
              allowedPorts:
                - 8091
            """;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final TargetProfileSpecMapper profileSpecMapper = new TargetProfileSpecMapper();
    private final RunSnapshotIntegrity integrity = new RunSnapshotIntegrity(objectMapper);

    /** JSONB와 같은 JSON round-trip 뒤에도 세 snapshot hash가 유효해야 한다. */
    @Test
    void snapshotRoundTripPreservesAllHashes() throws Exception {
        CompiledRunPlan original = validPlan();

        JsonNode stored = objectMapper.valueToTree(original);
        CompiledRunPlan restored = objectMapper.treeToValue(stored, CompiledRunPlan.class);

        integrity.verify(restored);
        assertThat(restored.planSchemaVersion()).isEqualTo(2);
        assertThat(restored.scenarioSnapshot().safeRawYaml())
                .contains("test-scenario")
                .doesNotContain("scenario-exact-yaml");
        assertThat(restored.snapshotSetHash()).isEqualTo(original.snapshotSetHash());
        assertThat(restored.targetProfileSnapshot().apiVersion()).isEqualTo(API_VERSION);
    }

    /** 외부 JSON tree와 nested 변수 변경이 저장된 plan을 바꾸지 않아야 한다. */
    @Test
    void snapshotAccessorsDefendAgainstNestedMutation() {
        ObjectNode detail = objectMapper.createObjectNode().put("path", "/original");
        List<String> nested = new ArrayList<>(List.of("first"));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("nested", nested);
        CompiledRunPlan base = validPlan();
        CompiledRunPlan plan = new CompiledRunPlan(
                base.runId(), base.scenarioName(), base.scenarioVersion(), base.scenarioHash(),
                base.contractName(), base.contractVersion(), base.emulatorAlias(),
                base.targetProfileName(), base.seed(), base.timeoutMs(), base.createdAt(), variables,
                List.of(new CompiledRunPlan.CompiledStep("s1", StepType.TARGET_HTTP, 0, detail)),
                base.planSchemaVersion(), base.scenarioSnapshot(), base.contractSnapshot(),
                base.targetProfileSnapshot(), base.snapshotSetHash());

        nested.add("mutated");
        detail.put("path", "/mutated");
        ((ObjectNode) plan.scenarioSnapshot().safeSpec()).put("mutated", true);
        ((ObjectNode) plan.steps().get(0).detail()).put("path", "/accessor-mutated");

        assertThat(plan.variables().get("nested")).isEqualTo(List.of("first"));
        assertThat(plan.steps().get(0).detail().path("path").asText()).isEqualTo("/original");
        assertThat(plan.scenarioSnapshot().safeSpec().has("mutated")).isFalse();
    }

    /** 상위 snapshot set hash 변조는 실행 전에 거부돼야 한다. */
    @Test
    void tamperedSnapshotSetHashIsRejected() {
        CompiledRunPlan base = validPlan();
        CompiledRunPlan tampered = copyPlan(base, HashValue.sha256("tampered-set").hexValue(),
                base.scenarioSnapshot());

        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("snapshot set hash");
    }

    /** Scenario contractRef와 Contract snapshot identity가 다르면 거부돼야 한다. */
    @Test
    void mismatchedScenarioContractReferenceIsRejected() {
        CompiledRunPlan base = validPlan();
        ObjectNode changedSpec = (ObjectNode) base.scenarioSnapshot().safeSpec();
        changedSpec.withObject("contractRef").put("name", "other-contract");
        ArtifactSnapshot changedScenario = artifactSnapshot(ArtifactKind.SCENARIO,
                "test-scenario", "1.0.0", "scenario-exact-yaml", changedSpec);
        String setHash = RunSnapshotIntegrity.snapshotSetHash(2, changedScenario,
                base.contractSnapshot(), base.targetProfileSnapshot());
        CompiledRunPlan mismatched = copyPlan(base, setHash, changedScenario);

        assertThatThrownBy(() -> integrity.verify(mismatched))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("contractRef");
    }

    /** canonical hash를 다시 맞춰도 safe YAML에 comment를 주입하면 거부돼야 한다. */
    @Test
    void rawYamlAndSourceHashMismatchIsRejected() {
        CompiledRunPlan base = validPlan();
        ArtifactSnapshot original = base.scenarioSnapshot();
        String changedRaw = original.safeRawYaml() + "# injected-comment\n";
        ObjectNode material = objectMapper.createObjectNode();
        material.put("kind", original.kind().name());
        material.put("apiVersion", original.apiVersion());
        material.put("name", original.name());
        material.put("version", original.version());
        material.put("sourceContentHash", original.sourceContentHash());
        String changedSafeHash = HashValue.sha256(changedRaw).hexValue();
        material.put("safeContentHash", changedSafeHash);
        material.put("safeRawYaml", changedRaw);
        material.set("safeSpec", original.safeSpec());
        ArtifactSnapshot changed = new ArtifactSnapshot(
                original.kind(), original.apiVersion(), original.name(), original.version(),
                original.sourceContentHash(), changedSafeHash,
                CanonicalJson.sha256(objectMapper, material), changedRaw, original.safeSpec());
        CompiledRunPlan tampered = copyPlan(base,
                RunSnapshotIntegrity.snapshotSetHash(2, changed,
                        base.contractSnapshot(), base.targetProfileSnapshot()), changed);

        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOfSatisfying(ArtifactImportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED))
                .hasMessageContaining("canonical snapshot");
    }

    /** null flat identity도 NPE가 아니라 snapshot 손상 코드로 귀결돼야 한다. */
    @Test
    void nullFlatMetadataIsReportedAsCorruption() {
        CompiledRunPlan base = validPlan();
        CompiledRunPlan missingName = new CompiledRunPlan(
                base.runId(), null, base.scenarioVersion(), base.scenarioHash(),
                base.contractName(), base.contractVersion(), base.emulatorAlias(),
                base.targetProfileName(), base.seed(), base.timeoutMs(), base.createdAt(),
                base.variables(), base.steps(), base.planSchemaVersion(), base.scenarioSnapshot(),
                base.contractSnapshot(), base.targetProfileSnapshot(), base.snapshotSetHash());

        assertThatThrownBy(() -> integrity.verify(missingName))
                .isInstanceOfSatisfying(ArtifactImportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED));
    }

    /** 실제 Profile YAML·import spec·실행 모델이 같으면 안전 snapshot을 생성해야 한다. */
    @Test
    void matchingProfileSourceSpecAndRuntimeModelProduceSnapshot() {
        VersionedTargetProfile definition = validVersionedProfile();

        TargetProfileSafeSnapshot snapshot = integrity.snapshot(definition);

        assertThat(snapshot.name()).isEqualTo("test-target");
        assertThat(snapshot.sourceContentHash())
                .isEqualTo(HashValue.sha256(PROFILE_YAML).hexValue());
        assertThat(snapshot.profile()).isEqualTo(definition.profile());
        assertThat(snapshot.hasValidCanonicalHash(objectMapper)).isTrue();
    }

    /** 원본 hash가 유효해도 raw YAML spec과 import spec이 다르면 손상으로 거부해야 한다. */
    @Test
    void validRawHashWithDifferentParsedSpecIsRejected() {
        ObjectNode changedSpec = (ObjectNode) profileSpec(PROFILE_YAML).deepCopy();
        changedSpec.putArray("allowedMethods").add("GET").add("DELETE");
        TargetProfile changedProfile = profileSpecMapper.toProfile("test-target", changedSpec);
        VersionedTargetProfile definition = versionedProfile(
                PROFILE_YAML, changedSpec, changedProfile);

        assertThatThrownBy(() -> integrity.snapshot(definition))
                .isInstanceOfSatisfying(ArtifactImportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED))
                .hasMessageContaining("YAML spec과 import spec");
    }

    /** raw YAML과 import spec이 같아도 실행 모델만 바뀌면 손상으로 거부해야 한다. */
    @Test
    void parsedSpecWithDifferentExecutableProfileIsRejected() {
        JsonNode parsedSpec = profileSpec(PROFILE_YAML);
        TargetProfile mapped = profileSpecMapper.toProfile("test-target", parsedSpec);
        TargetProfile changedProfile = new TargetProfile(
                mapped.name(), mapped.baseUri(), mapped.allowedPaths(), List.of("DELETE"),
                mapped.allowedHosts(), mapped.allowedPorts(), mapped.defaultHeaders(),
                mapped.secretHeaders(), mapped.callbackPaths(), mapped.redactionHeaderNames(),
                mapped.redactionJsonPaths());
        VersionedTargetProfile definition = versionedProfile(
                PROFILE_YAML, parsedSpec, changedProfile);

        assertThatThrownBy(() -> integrity.snapshot(definition))
                .isInstanceOfSatisfying(ArtifactImportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED))
                .hasMessageContaining("import spec과 실행 모델");
    }

    /** raw YAML envelope의 name이 선택된 Profile identity와 다르면 손상으로 거부해야 한다. */
    @Test
    void mismatchedProfileEnvelopeIsRejected() {
        String mismatchedRaw = PROFILE_YAML.replace(
                "name: test-target", "name: other-target");
        JsonNode parsedSpec = profileSpec(mismatchedRaw);
        TargetProfile profile = profileSpecMapper.toProfile("test-target", parsedSpec);
        VersionedTargetProfile definition = versionedProfile(
                mismatchedRaw, parsedSpec, profile);

        assertThatThrownBy(() -> integrity.snapshot(definition))
                .isInstanceOfSatisfying(ArtifactImportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED))
                .hasMessageContaining("YAML envelope");
    }

    /**
     * plan 일부만 바꾼 복사본을 만든다.
     *
     * @param base 원본 plan
     * @param setHash 적용할 상위 hash
     * @param scenario 적용할 Scenario snapshot
     * @return 변경된 plan
     */
    private CompiledRunPlan copyPlan(CompiledRunPlan base, String setHash,
                                     ArtifactSnapshot scenario) {
        return new CompiledRunPlan(base.runId(), scenario.name(), scenario.version(),
                scenario.sourceContentHash(), base.contractName(), base.contractVersion(),
                base.emulatorAlias(), base.targetProfileName(), base.seed(), base.timeoutMs(),
                base.createdAt(), base.variables(), base.steps(), base.planSchemaVersion(), scenario,
                base.contractSnapshot(), base.targetProfileSnapshot(), setHash);
    }

    /**
     * 모든 무결성 값을 갖춘 최소 v2 plan을 만든다.
     *
     * @return 검증 가능한 plan
     */
    private CompiledRunPlan validPlan() {
        ObjectNode scenarioSpec = objectMapper.createObjectNode();
        scenarioSpec.putObject("contractRef").put("name", "test-contract").put("version", "1.0.0");
        scenarioSpec.putArray("steps");
        ArtifactSnapshot scenario = artifactSnapshot(ArtifactKind.SCENARIO,
                "test-scenario", "1.0.0", "scenario-exact-yaml", scenarioSpec);
        ArtifactSnapshot contract = artifactSnapshot(ArtifactKind.PROVIDER_CONTRACT,
                "test-contract", "1.0.0", "contract-exact-yaml",
                objectMapper.createObjectNode().put("emulatorAlias", "test-alias"));
        TargetProfileSafeSnapshot profile = profileSnapshot();
        String setHash = RunSnapshotIntegrity.snapshotSetHash(2, scenario, contract, profile);
        return new CompiledRunPlan("run-1", scenario.name(), scenario.version(),
                scenario.sourceContentHash(), contract.name(), contract.version(), "test-alias",
                profile.name(), 7L, 30_000L, Instant.EPOCH, Map.of(), List.of(), 2,
                scenario, contract, profile, setHash);
    }

    /**
     * 실제 원본 hash와 comment 제거 검증을 통과하는 Artifact snapshot을 만든다.
     */
    private ArtifactSnapshot artifactSnapshot(ArtifactKind kind, String name, String version,
                                              String commentMarker, JsonNode spec) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("apiVersion", API_VERSION);
        root.put("kind", kind == ArtifactKind.SCENARIO ? "Scenario" : "ProviderContract");
        root.putObject("metadata")
                .put("name", name)
                .put("displayName", name)
                .put("version", version);
        root.set("spec", spec);
        String rawYaml;
        try {
            rawYaml = new ObjectMapper(new YAMLFactory()).writeValueAsString(root)
                    + "# " + commentMarker + "\n";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        ArtifactDefinition definition = new ArtifactDefinition(name + "-id", kind,
                new ArtifactMetadata(name, name, version, Map.of()), API_VERSION,
                HashValue.sha256(rawYaml), rawYaml, spec, false, Instant.EPOCH, null);
        return integrity.snapshot(definition, kind);
    }

    /** 실제 YAML fixture와 의미가 결속된 Profile snapshot을 만든다. */
    private TargetProfileSafeSnapshot profileSnapshot() {
        return integrity.snapshot(validVersionedProfile());
    }

    /** 실제 YAML fixture에서 import spec과 실행 모델이 일치하는 versioned Profile을 만든다. */
    private VersionedTargetProfile validVersionedProfile() {
        JsonNode parsedSpec = profileSpec(PROFILE_YAML);
        TargetProfile profile = profileSpecMapper.toProfile("test-target", parsedSpec);
        return versionedProfile(PROFILE_YAML, parsedSpec, profile);
    }

    /**
     * 명시한 원본·spec·실행 모델을 한 versioned Profile fixture로 결합한다.
     *
     * @param rawYaml source hash 계산에 사용할 실제 YAML
     * @param parsedSpec import 경계가 저장한 spec
     * @param profile 실행 경계가 사용할 Profile
     * @return snapshot 입력용 versioned Profile
     */
    private VersionedTargetProfile versionedProfile(String rawYaml, JsonNode parsedSpec,
                                                     TargetProfile profile) {
        return new VersionedTargetProfile(API_VERSION, "1.0.0",
                HashValue.sha256(rawYaml).hexValue(), rawYaml, parsedSpec, profile);
    }

    /** 실제 YAML fixture에서 spec tree를 읽고 fixture 오류는 즉시 테스트 실패로 바꾼다. */
    private JsonNode profileSpec(String rawYaml) {
        try {
            return yamlMapper.readTree(rawYaml).path("spec");
        } catch (Exception exception) {
            throw new IllegalStateException("Target Profile YAML fixture를 읽을 수 없습니다.",
                    exception);
        }
    }
}
