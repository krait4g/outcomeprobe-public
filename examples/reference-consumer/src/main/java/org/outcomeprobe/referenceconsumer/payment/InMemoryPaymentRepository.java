package org.outcomeprobe.referenceconsumer.payment;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Repository;

/**
 * ConcurrentHashMap 기반 in-memory 결제 저장소다.
 *
 * <p>책임: 데모 Consumer의 상태를 재시작마다 초기화되는 가벼운 저장소에
 * 보관하고, compute 기반 원자 전이를 제공한다.</p>
 * <p>핵심 불변식: update는 ConcurrentHashMap.compute로 직렬화돼 동일 결제의
 * 병행 갱신이 유실·중복되지 않는다.</p>
 */
@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

    private final ConcurrentHashMap<String, Payment> store = new ConcurrentHashMap<>();

    /**
     * 빈 저장소를 생성한다.
     */
    public InMemoryPaymentRepository() {
    }

    /**
     * 결제를 조회한다.
     *
     * @param paymentId 결제 식별자
     * @return 존재하면 결제
     */
    @Override
    public Optional<Payment> findById(String paymentId) {
        return Optional.ofNullable(store.get(paymentId));
    }

    /**
     * 없으면 초기 결제를 생성한다.
     *
     * @param paymentId 결제 식별자
     * @param amount 승인 요청 금액
     * @return 기존 또는 생성된 결제
     */
    @Override
    public Payment findOrCreate(String paymentId, long amount) {
        return store.computeIfAbsent(paymentId, key -> Payment.created(key, amount));
    }

    /**
     * compute로 원자 갱신한다.
     *
     * @param paymentId 결제 식별자
     * @param updater 상태 변환 함수
     * @return 갱신된 결제, 존재하지 않으면 empty
     */
    @Override
    public Optional<Payment> update(String paymentId, UnaryOperator<Payment> updater) {
        // compute 안의 변환은 동일 key에 대해 직렬 실행돼 중복 ledger 반영을 막는다.
        Payment updated = store.computeIfPresent(paymentId, (key, payment) -> updater.apply(payment));
        return Optional.ofNullable(updated);
    }

    /**
     * 모든 결제를 반환한다.
     *
     * @return 결제 컬렉션 snapshot
     */
    @Override
    public Collection<Payment> findAll() {
        return store.values();
    }

    /**
     * 결제를 삭제한다.
     *
     * @param paymentId 결제 식별자
     */
    @Override
    public void delete(String paymentId) {
        store.remove(paymentId);
    }
}
