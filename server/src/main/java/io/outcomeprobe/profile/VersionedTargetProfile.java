package io.outcomeprobe.profile;

import tools.jackson.databind.JsonNode;

/**
 * Run 생성 트랜잭션에서 선택한 TargetProfile Artifact와 타입 안전 모델의 결합이다.
 *
 * <p>이 객체는 snapshot을 만들기 전 원본 content hash를 재검증하는 데 사용된다.</p>
 *
 * @param apiVersion TargetProfile DSL API 버전
 * @param version 선택된 Profile Artifact 버전
 * @param sourceContentHash 저장된 Profile YAML의 SHA-256
 * @param rawYaml hash 재검증 대상 YAML
 * @param parsedSpec import 시 검증된 Profile spec
 * @param profile 실행에 사용하는 타입 안전 Profile
 */
public record VersionedTargetProfile(
        String apiVersion,
        String version,
        String sourceContentHash,
        String rawYaml,
        JsonNode parsedSpec,
        TargetProfile profile) {

    /**
     * import 당시 parsed spec을 방어적으로 복사해 snapshot 경계 밖 변조를 막는다.
     */
    public VersionedTargetProfile {
        parsedSpec = parsedSpec == null ? null : parsedSpec.deepCopy();
    }

    /**
     * 저장된 Artifact spec을 외부에서 변경하지 못하도록 복사본을 반환한다.
     *
     * @return parsed spec 복사본
     */
    @Override
    public JsonNode parsedSpec() {
        return parsedSpec == null ? null : parsedSpec.deepCopy();
    }
}
