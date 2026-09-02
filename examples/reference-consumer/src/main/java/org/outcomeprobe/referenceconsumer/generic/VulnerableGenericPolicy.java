package org.outcomeprobe.referenceconsumer.generic;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 취약 generic 정책: 중복 제거 없이 매번 처리 횟수를 증가시킨다.
 *
 * <p>책임: 중복 이벤트가 side effect 중복으로 이어지는 취약 구현을 재현한다.</p>
 */
@Component
@Profile("vulnerable")
public class VulnerableGenericPolicy implements GenericEventPolicy {

    private final GenericEntityStore entityStore;

    /**
     * 엔티티 저장소를 주입한다.
     *
     * @param entityStore 엔티티 저장소
     */
    public VulnerableGenericPolicy(GenericEntityStore entityStore) {
        this.entityStore = entityStore;
    }

    /**
     * 이벤트마다 무조건 상태를 덮어쓰고 처리 횟수를 증가시킨다.
     *
     * @param entityId 엔티티 식별자
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param version 이벤트 version
     */
    @Override
    public void handleGenericEvent(String entityId, String eventId, String state, long version) {
        entityStore.findOrCreate(entityId);
        entityStore.update(entityId, entity -> entity.applyUnsafe(state, version));
    }
}
