package org.outcomeprobe.referenceconsumer.generic;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Generic Webhook 시나리오 검증을 위한 최소 엔티티다.
 *
 * <p>책임: Provider API 없이 Webhook 전달만으로 상태와 side effect 횟수가
 * 어떻게 달라지는지 보여준다.</p>
 *
 * @param entityId 엔티티 식별자
 * @param state 현재 상태
 * @param version 반영된 이벤트 version
 * @param processedEventCount 이벤트 처리 횟수
 * @param processedEventIds 처리한 이벤트 ID 집합
 */
public record GenericEntity(
        String entityId,
        String state,
        long version,
        int processedEventCount,
        Set<String> processedEventIds) {

    /**
     * 초기 엔티티를 만든다.
     *
     * @param entityId 엔티티 식별자
     * @return READY 상태 엔티티
     */
    public static GenericEntity initial(String entityId) {
        return new GenericEntity(entityId, "READY", 0, 0, Set.of());
    }

    /**
     * 검증 없이 이벤트를 적용하고 처리 횟수를 항상 증가시킨다.
     *
     * @param state 이벤트 상태
     * @param eventVersion 이벤트 version
     * @return 적용된 새 엔티티
     */
    public GenericEntity applyUnsafe(String state, long eventVersion) {
        return new GenericEntity(entityId, state, eventVersion, processedEventCount + 1, processedEventIds);
    }

    /**
     * eventId 중복 제거와 version guard로 이벤트를 적용한다.
     *
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param eventVersion 이벤트 version
     * @return 적용됐으면 새 엔티티, 무시됐으면 empty
     */
    public Optional<GenericEntity> applyGuarded(String eventId, String state, long eventVersion) {
        if (processedEventIds.contains(eventId)) {
            return Optional.empty();
        }
        Set<String> events = new LinkedHashSet<>(processedEventIds);
        events.add(eventId);
        if (eventVersion <= version) {
            return Optional.of(new GenericEntity(entityId, this.state, version,
                    processedEventCount, Set.copyOf(events)));
        }
        return Optional.of(new GenericEntity(entityId, state, eventVersion,
                processedEventCount + 1, Set.copyOf(events)));
    }
}
