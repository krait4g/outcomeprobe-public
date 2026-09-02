package org.outcomeprobe.referenceconsumer.generic;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 복원력 generic 정책: eventId 중복 제거와 version guard로 처리한다.
 *
 * <p>책임: 중복·역순 이벤트에서 처리 횟수와 상태가 손상되지 않게 한다.</p>
 */
@Component
@Profile("resilient")
public class ResilientGenericPolicy implements GenericEventPolicy {

    private final GenericEntityStore entityStore;

    /**
     * 엔티티 저장소를 주입한다.
     *
     * @param entityStore 엔티티 저장소
     */
    public ResilientGenericPolicy(GenericEntityStore entityStore) {
        this.entityStore = entityStore;
    }

    /**
     * 중복 제거와 version guard로 이벤트를 적용한다.
     *
     * @param entityId 엔티티 식별자
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param version 이벤트 version
     */
    @Override
    public void handleGenericEvent(String entityId, String eventId, String state, long version) {
        entityStore.findOrCreate(entityId);
        entityStore.update(entityId, entity ->
                entity.applyGuarded(eventId, state, version).orElse(entity));
    }
}
