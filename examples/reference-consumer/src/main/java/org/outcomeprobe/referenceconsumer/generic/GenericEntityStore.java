package org.outcomeprobe.referenceconsumer.generic;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Repository;

/**
 * in-memory generic 엔티티 저장소다.
 *
 * <p>책임: Webhook 시나리오 검증용 엔티티와 의도적 503 주입 카운터를 보관한다.</p>
 */
@Repository
public class GenericEntityStore {

    private final ConcurrentHashMap<String, GenericEntity> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failNextCounts = new ConcurrentHashMap<>();

    /**
     * 빈 저장소를 생성한다.
     */
    public GenericEntityStore() {
    }

    /**
     * 엔티티를 조회한다.
     *
     * @param entityId 엔티티 식별자
     * @return 존재하면 엔티티
     */
    public Optional<GenericEntity> findById(String entityId) {
        return Optional.ofNullable(store.get(entityId));
    }

    /**
     * 없으면 초기 엔티티를 생성한다.
     *
     * @param entityId 엔티티 식별자
     * @return 기존 또는 생성된 엔티티
     */
    public GenericEntity findOrCreate(String entityId) {
        return store.computeIfAbsent(entityId, GenericEntity::initial);
    }

    /**
     * 원자 변환으로 엔티티를 갱신한다.
     *
     * @param entityId 엔티티 식별자
     * @param updater 변환 함수
     */
    public void update(String entityId, UnaryOperator<GenericEntity> updater) {
        store.computeIfPresent(entityId, (key, entity) -> updater.apply(entity));
    }

    /**
     * 엔티티를 삭제한다.
     *
     * @param entityId 엔티티 식별자
     */
    public void delete(String entityId) {
        store.remove(entityId);
        failNextCounts.remove(entityId);
    }

    /**
     * 다음 N회 Webhook 요청을 503으로 응답하도록 설정한다.
     *
     * <p>왜 필요한가: retry-after-503 Scenario가 Target의 일시 실패를 재현해야
     * Delivery 재시도 검증을 할 수 있다.</p>
     *
     * @param entityId 엔티티 식별자
     * @param count 503 응답 횟수
     */
    public void failNext(String entityId, int count) {
        failNextCounts.put(entityId, new AtomicInteger(count));
    }

    /**
     * 503 주입 카운터를 소모하고 실패 응답 여부를 반환한다.
     *
     * @param entityId 엔티티 식별자
     * @return 503을 응답해야 하면 true
     */
    public boolean consumeFailure(String entityId) {
        AtomicInteger counter = failNextCounts.get(entityId);
        if (counter == null) {
            return false;
        }
        int remaining = counter.getAndUpdate(value -> value > 0 ? value - 1 : 0);
        if (remaining <= 0) {
            failNextCounts.remove(entityId);
            return false;
        }
        return true;
    }
}
