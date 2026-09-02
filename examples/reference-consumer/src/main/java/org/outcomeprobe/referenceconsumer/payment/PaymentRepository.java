package org.outcomeprobe.referenceconsumer.payment;

import java.util.Collection;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 결제 상태 저장소 포트다.
 *
 * <p>책임: 데모 Consumer가 저장 매체에 결합되지 않게 하고, 원자 상태 전이
 * 연산을 제공해 중복 side effect를 막는다.</p>
 * <p>외부에서 오해하기 쉬운 한계: v1 구현은 in-memory 저장소다. 프로세스
 * 재시작 시 상태가 사라지며 이것은 데모 의도다.</p>
 */
public interface PaymentRepository {

    /**
     * 결제를 조회한다.
     *
     * @param paymentId 결제 식별자
     * @return 존재하면 결제
     */
    Optional<Payment> findById(String paymentId);

    /**
     * 없으면 초기 결제를 생성하고 반환한다.
     *
     * @param paymentId 결제 식별자
     * @param amount 승인 요청 금액
     * @return 기존 또는 생성된 결제
     */
    Payment findOrCreate(String paymentId, long amount);

    /**
     * 원자 변환 함수로 결제 상태를 갱신한다.
     *
     * <p>왜 원자 연산인가: 동일 결제의 중복 승인·조회 수렴이 경합할 때
     * last-write-wins로 side effect가 중복되면 취약·복원력 비교가 무의미해진다.</p>
     *
     * @param paymentId 결제 식별자
     * @param updater 상태 변환 함수
     * @return 갱신된 결제, 존재하지 않으면 empty
     */
    Optional<Payment> update(String paymentId, UnaryOperator<Payment> updater);

    /**
     * 모든 결제를 조회한다.
     *
     * @return 결제 컬렉션
     */
    Collection<Payment> findAll();

    /**
     * 결제를 삭제한다.
     *
     * @param paymentId 결제 식별자
     */
    void delete(String paymentId);
}
