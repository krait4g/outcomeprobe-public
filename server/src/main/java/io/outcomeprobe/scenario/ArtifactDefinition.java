package io.outcomeprobe.scenario;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.foundation.HashValue;
import java.time.Instant;

/**
 * 저장소에 영속화된 immutable Artifact를 나타낸다.
 *
 * <p>책임: import된 원본 YAML과 해석 결과를 content hash와 함께 보존해,
 * 원본 파일이 수정돼도 과거 Run snapshot이 변하지 않는 근거를 제공한다.</p>
 * <p>핵심 불변식: 동일 kind+name+version의 내용은 영원히 같은 contentHash를 가진다.
 * 삭제는 soft delete이며 과거 Run은 snapshot으로 남는다.</p>
 *
 * @param id Artifact 고유 식별자
 * @param kind Artifact 종류
 * @param metadata 공통 metadata
 * @param apiVersion DSL API 버전
 * @param contentHash 원본 YAML의 SHA-256 해시
 * @param rawYaml 원본 YAML 문자열
 * @param parsedJson 해석된 spec JSON 트리
 * @param builtIn 서버 내장 여부
 * @param importedAt import 시각
 * @param deletedAt soft delete 시각, 활성이면 null
 */
public record ArtifactDefinition(
        String id,
        ArtifactKind kind,
        ArtifactMetadata metadata,
        String apiVersion,
        HashValue contentHash,
        String rawYaml,
        JsonNode parsedJson,
        boolean builtIn,
        Instant importedAt,
        Instant deletedAt) {

    /**
     * 활성 상태 여부, 즉 soft delete되지 않았는지를 반환한다.
     *
     * @return 활성이면 true
     */
    public boolean isActive() {
        return deletedAt == null;
    }
}
