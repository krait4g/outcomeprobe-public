package org.outcomeprobe.referenceconsumer.generic;

/**
 * Generic Webhook 이벤트 처리 정책이다.
 *
 * <p>책임: 중복·역순 이벤트 처리 차이를 전략 객체로 분리한다.</p>
 */
public interface GenericEventPolicy {

    /**
     * generic 상태 변경 이벤트를 처리한다.
     *
     * @param entityId 엔티티 식별자
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param version 이벤트 version
     */
    void handleGenericEvent(String entityId, String eventId, String state, long version);
}
