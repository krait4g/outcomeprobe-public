package io.outcomeprobe.delivery;

import tools.jackson.databind.JsonNode;

/**
 * 하나의 업무 사건(Callback Event)을 나타낸다.
 *
 * <p>책임: eventId와 payload가 고정된 업무 사건을 전달 occurrence들과 분리한다.
 * 중복 Webhook은 같은 CallbackEvent를 참조하는 여러 전달 occurrence다.</p>
 * <p>핵심 불변식: 동일 Run에서 같은 eventId는 하나만 존재한다. payload는 생성
 * 시점의 hash와 함께 보존돼 전달마다 내용이 바뀌지 않는다.</p>
 *
 * @param id 행 식별자
 * @param runId 소속 Run 식별자
 * @param callbackKey Contract callback key
 * @param eventId 업무 사건 식별자
 * @param eventType Contract event 유형
 * @param resourceType 관련 resource 유형
 * @param resourceId 관련 resource 식별자
 * @param resourceVersion resource 버전
 * @param payload 렌더링된 event payload
 * @param payloadHash payload 해시
 */
public record CallbackEvent(
        String id,
        String runId,
        String callbackKey,
        String eventId,
        String eventType,
        String resourceType,
        String resourceId,
        Long resourceVersion,
        JsonNode payload,
        String payloadHash) {
}
