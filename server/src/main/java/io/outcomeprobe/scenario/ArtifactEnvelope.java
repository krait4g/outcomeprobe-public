package io.outcomeprobe.scenario;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.foundation.HashValue;

/**
 * 모든 Artifact가 공유하는 공통 envelope이다.
 *
 * <p>책임: apiVersion·kind·metadata·spec 구조를 확정하고 content hash를 제공해
 * immutable version 계약과 import 충돌 검사의 기준을 만든다.</p>
 * <p>핵심 불변식: contentHash는 원본 YAML 전체의 SHA-256이다. 동일
 * kind+name+version에서 hash가 다르면 수정본이므로 import가 거부된다.</p>
 *
 * @param apiVersion DSL API 버전
 * @param kind Artifact 종류
 * @param metadata 공통 metadata
 * @param spec 종류별 상세 정의를 담은 JSON 트리
 * @param rawYaml 원본 YAML 문자열
 * @param contentHash 원본 YAML의 SHA-256 해시
 */
public record ArtifactEnvelope(
        String apiVersion,
        ArtifactKind kind,
        ArtifactMetadata metadata,
        JsonNode spec,
        String rawYaml,
        HashValue contentHash) {
}
