package org.outcomeprobe.referenceconsumer.payment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 복원력 정책: eventId 중복 제거 후 원장을 정확히 1회 반영한다.
 *
 * <p>책임: 같은 eventId가 여러 번 도착해도 processedEventIds로 첫 처리만
 * 원장에 반영해 side effect 중복을 막는다.</p>
 * <p>핵심 불변식: 중복 여부는 상태 갱신과 원자적으로 판단된다. compute 밖에서
 * 먼저 확인하고 나중에 갱신하면 경합에서 중복 반영이 발생할 수 있다.</p>
 */
@Component
@Profile("resilient")
public class ResilientPaymentEventPolicy implements PaymentEventPolicy {

    private final PaymentRepository paymentRepository;

    /**
     * 결제 저장소를 주입한다.
     *
     * @param paymentRepository 결제 저장소
     */
    public ResilientPaymentEventPolicy(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * eventId가 처음 처리될 때만 원장을 반영한다.
     *
     * @param paymentId 결제 식별자
     * @param eventId 이벤트 식별자
     */
    @Override
    public void handlePaymentSucceeded(String paymentId, String eventId) {
        // 복원력 구현: compute 안에서 중복 판단과 반영을 원자로 수행한다.
        paymentRepository.update(paymentId, payment ->
                payment.withSuccessEventIfNew(eventId).orElse(payment));
    }
}
