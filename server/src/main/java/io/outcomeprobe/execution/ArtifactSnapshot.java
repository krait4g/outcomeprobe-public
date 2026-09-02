package io.outcomeprobe.execution;

import io.outcomeprobe.scenario.ArtifactKind;
import tools.jackson.databind.JsonNode;

/**
 * Run 생성 시 고정한 Scenario 또는 ProviderContract의 안전한 snapshot이다.
 *
 * @param kind Artifact 종류
 * @param apiVersion DSL API 버전
 * @param name Artifact 이름
 * @param version Artifact SemVer
 * @param sourceContentHash import 원본 YAML의 SHA-256
 * @param safeContentHash comment-free safeRawYaml의 SHA-256
 * @param canonicalSnapshotHash identity·두 content hash·safeRawYaml·safeSpec의 SHA-256
 * @param safeRawYaml 주석을 제거하고 문서 전체 credential 검사를 통과한 안전 YAML
 * @param safeSpec 실행에 필요한 검증된 spec JSON
 */
public record ArtifactSnapshot(
        ArtifactKind kind,
        String apiVersion,
        String name,
        String version,
        String sourceContentHash,
        String safeContentHash,
        String canonicalSnapshotHash,
        String safeRawYaml,
        JsonNode safeSpec) {

    /**
     * 실행 spec JSON을 방어적으로 복사해 호출자와 가변 tree를 공유하지 않는다.
     */
    public ArtifactSnapshot {
        safeSpec = safeSpec == null ? null : safeSpec.deepCopy();
    }

    /**
     * 내부 snapshot tree를 외부에서 변경하지 못하도록 복사본을 반환한다.
     *
     * @return safe spec 복사본
     */
    @Override
    public JsonNode safeSpec() {
        return safeSpec == null ? null : safeSpec.deepCopy();
    }
}
