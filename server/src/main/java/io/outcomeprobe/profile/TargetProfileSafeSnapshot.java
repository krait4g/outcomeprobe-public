package io.outcomeprobe.profile;

import io.outcomeprobe.foundation.CanonicalJson;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Run 시작 시 고정한 Target Profile의 secret-free 실행 snapshot이다.
 *
 * <p>{@link TargetProfile}에는 secret 원문이 없고 secretRef 이름만 존재한다.</p>
 *
 * @param apiVersion DSL API 버전
 * @param name Profile 이름
 * @param version 선택된 Profile SemVer
 * @param sourceContentHash import 원본 YAML의 SHA-256
 * @param canonicalSnapshotHash profile canonical JSON의 SHA-256
 * @param profile 실행에 사용할 불변 Profile
 */
public record TargetProfileSafeSnapshot(
        String apiVersion,
        String name,
        String version,
        String sourceContentHash,
        String canonicalSnapshotHash,
        TargetProfile profile) {

    /**
     * Snapshot identity·source hash·safe Profile 전체의 canonical hash를 계산한다.
     *
     * @param objectMapper JSON mapper
     * @param apiVersion DSL API 버전
     * @param name Profile 이름
     * @param version Profile SemVer
     * @param sourceContentHash exact YAML source hash
     * @param profile safe Profile 모델
     * @return canonical SHA-256
     */
    public static String calculateCanonicalHash(ObjectMapper objectMapper, String apiVersion,
                                                String name, String version,
                                                String sourceContentHash, TargetProfile profile) {
        ObjectNode material = objectMapper.createObjectNode();
        material.put("apiVersion", apiVersion);
        material.put("name", name);
        material.put("version", version);
        material.put("sourceContentHash", sourceContentHash);
        material.set("profile", objectMapper.valueToTree(profile));
        return CanonicalJson.sha256(objectMapper, material);
    }

    /**
     * 저장된 canonical hash가 현재 snapshot 내용과 일치하는지 검사한다.
     *
     * @param objectMapper JSON mapper
     * @return snapshot이 완전하고 hash가 일치하면 true
     */
    public boolean hasValidCanonicalHash(ObjectMapper objectMapper) {
        return profile != null && name != null && name.equals(profile.name())
                && canonicalSnapshotHash != null
                && canonicalSnapshotHash.equals(calculateCanonicalHash(objectMapper, apiVersion,
                        name, version, sourceContentHash, profile));
    }
}
