package io.outcomeprobe.scenario;

import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.adapter.out.persistence.JdbcArtifactRepository;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.ScenarioDefinition;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Artifact validate·import 정책을 조정하는 애플리케이션 서비스다.
 *
 * <p>책임: envelope 해석, 종류별 구조 검증, content hash 충돌 규칙, 참조 검증을
 * 순서대로 적용해 저장소에 immutable Artifact만 들어가게 한다.</p>
 * <p>핵심 불변식: 동일 kind+name+version에 다른 content hash를 가진 문서는
 * 수정본이므로 import를 거부한다. 수정은 새 version으로만 가능하다.</p>
 */
@Service
public class ArtifactImportService {

    private final ArtifactParser parser;
    private final ScenarioMapper scenarioMapper;
    private final JdbcArtifactRepository artifactRepository;

    /**
     * import 정책 수행에 필요한 협업 객체를 주입한다.
     *
     * @param parser YAML envelope 해석기
     * @param scenarioMapper 종류별 구조 변환·검증기
     * @param artifactRepository Artifact 저장소
     */
    public ArtifactImportService(ArtifactParser parser, ScenarioMapper scenarioMapper,
                                 JdbcArtifactRepository artifactRepository) {
        this.parser = parser;
        this.scenarioMapper = scenarioMapper;
        this.artifactRepository = artifactRepository;
    }

    /**
     * 원본 YAML을 저장 없이 검증만 수행한다.
     *
     * @param rawYaml 원본 YAML 문자열
     * @return 검증된 envelope
     * @throws ArtifactImportException 검증 실패 시 발생한다
     */
    public ArtifactEnvelope validate(String rawYaml) {
        ArtifactEnvelope envelope = parser.parse(rawYaml);
        rejectUnsupportedKind(envelope);
        validateKindSpecific(envelope, false);
        return envelope;
    }

    /**
     * 원본 YAML을 검증하고 저장한다. 동일 내용 재시도는 멱등으로 기존 정의를 반환한다.
     *
     * <p>왜 멱등인가: CI와 CLI가 같은 Artifact를 반복 import해도 충돌 없이 같은
     * content hash로 수렴해야 자동화가 안전하다.</p>
     *
     * @param rawYaml 원본 YAML 문자열
     * @param builtIn 서버 내장 Artifact 여부
     * @return 저장되거나 이미 존재하던 Artifact 정의
     * @throws ArtifactImportException 검증 실패 또는 hash 충돌 시 발생한다
     */
    @Transactional
    public ArtifactDefinition importArtifact(String rawYaml, boolean builtIn) {
        ArtifactEnvelope envelope = parser.parse(rawYaml);
        rejectUnsupportedKind(envelope);
        validateKindSpecific(envelope, true);

        // 같은 identity의 기존 Artifact가 있으면 content hash로 수정 여부를 판정한다.
        Optional<ArtifactDefinition> existing = artifactRepository.findByIdentity(
                envelope.kind(), envelope.metadata().name(), envelope.metadata().version());
        if (existing.isPresent()) {
            if (existing.get().contentHash().equals(envelope.contentHash())) {
                return existing.get();
            }
            throw new ArtifactImportException(ProblemCode.DATABASE_CONFLICT,
                    "metadata.version",
                    "동일 kind+name+version에 다른 내용이 존재합니다. 새 version으로 import하세요.");
        }

        // 같은 내용이 다른 metadata로 등록되는 것을 막아 Artifact identity를 보호한다.
        Optional<ArtifactDefinition> sameContent = artifactRepository.findByContentHash(envelope.contentHash());
        if (sameContent.isPresent()) {
            ArtifactDefinition conflict = sameContent.get();
            if (conflict.kind() != envelope.kind()
                    || !conflict.metadata().name().equals(envelope.metadata().name())
                    || !conflict.metadata().version().equals(envelope.metadata().version())) {
                throw new ArtifactImportException(ProblemCode.DATABASE_CONFLICT,
                        "metadata",
                        "동일 content hash가 다른 metadata로 이미 등록되어 있습니다.");
            }
        }

        ArtifactDefinition definition = artifactRepository.newDefinition(envelope, builtIn);
        artifactRepository.save(definition);
        return definition;
    }

    /**
     * core-only 배포판이 제공하지 않는 Artifact 종류를 저장소 접근 전에 거부한다.
     *
     * @param envelope 공통 파싱을 마친 Artifact envelope
     */
    private void rejectUnsupportedKind(ArtifactEnvelope envelope) {
        if (envelope.kind() == ArtifactKind.SCENARIO_PACK) {
            throw new ArtifactImportException(
                    ProblemCode.ARTIFACT_KIND_NOT_SUPPORTED,
                    "kind",
                    "core-only 배포판에서 지원하지 않는 Artifact kind입니다: ScenarioPack");
        }
    }

    /**
     * 종류별 구조와 참조 계약을 검증한다.
     *
     * @param envelope 공통 검증을 통과한 Artifact envelope
     * @param checkReferences 저장소 참조까지 검증할지 여부
     */
    private void validateKindSpecific(ArtifactEnvelope envelope, boolean checkReferences) {
        ArtifactSecretPolicy.validateSafeSpec(envelope.spec());
        switch (envelope.kind()) {
            case PROVIDER_CONTRACT -> scenarioMapper.toContract(envelope.spec());
            case SCENARIO -> {
                ScenarioDefinition scenario = scenarioMapper.toScenario(envelope.spec());
                if (checkReferences) {
                    ArtifactDefinition contractArtifact = artifactRepository.findByIdentity(
                                    ArtifactKind.PROVIDER_CONTRACT,
                                    scenario.contractRef().name(),
                                    scenario.contractRef().version())
                            .orElseThrow(() -> new ArtifactImportException(
                                    ProblemCode.SCENARIO_REFERENCE_NOT_FOUND,
                                    "spec.contractRef",
                                    "참조하는 ProviderContract를 찾을 수 없습니다: "
                                            + scenario.contractRef().name()
                                            + "@" + scenario.contractRef().version()));
                    ProviderContract contract = scenarioMapper.toContract(contractArtifact.parsedJson());
                    scenarioMapper.validateReferences(scenario, contract);
                }
            }
            case TARGET_PROFILE -> {
                // 공통 정책과 JSON schema 검증만으로 Profile secret 경계를 확정한다.
            }
            case SCENARIO_PACK -> throw new IllegalStateException("지원하지 않는 kind가 검증 단계에 도달했습니다.");
        }
    }

}
