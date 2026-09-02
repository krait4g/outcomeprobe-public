package io.outcomeprobe.delivery;

import io.outcomeprobe.foundation.CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Callback 전달 요청의 모든 실행 의미를 하나의 canonical hash로 묶는다.
 *
 * <p>경로·서명·retry·payload binding·Profile snapshot 중 어느 하나라도 저장 후
 * 바뀌면 Worker가 외부 HTTP 전에 손상을 검출할 수 있게 한다.</p>
 */
public final class DeliveryRequestSnapshotHash {

    private static final String HASH_FIELD = "requestSnapshotHash";

    /** 인스턴스 생성을 막는다. */
    private DeliveryRequestSnapshotHash() {
    }

    /**
     * hash 필드 자신을 제외한 요청 snapshot의 SHA-256을 계산한다.
     *
     * @param objectMapper canonical JSON 변환 mapper
     * @param requestSnapshot Callback 전달 요청 snapshot
     * @return canonical request SHA-256
     */
    public static String calculate(ObjectMapper objectMapper, JsonNode requestSnapshot) {
        if (requestSnapshot == null || !requestSnapshot.isObject()) {
            return "";
        }
        ObjectNode material = (ObjectNode) requestSnapshot.deepCopy();
        material.remove(HASH_FIELD);
        return CanonicalJson.sha256(objectMapper, material);
    }

    /**
     * 저장된 hash와 현재 요청 의미가 일치하는지 확인한다.
     *
     * @param objectMapper canonical JSON 변환 mapper
     * @param requestSnapshot 검증할 Callback 요청 snapshot
     * @return hash가 존재하고 일치하면 true
     */
    public static boolean isValid(ObjectMapper objectMapper, JsonNode requestSnapshot) {
        return requestSnapshot != null
                && requestSnapshot.isObject()
                && !requestSnapshot.path(HASH_FIELD).asText("").isBlank()
                && requestSnapshot.path(HASH_FIELD).asText().equals(
                        calculate(objectMapper, requestSnapshot));
    }
}
