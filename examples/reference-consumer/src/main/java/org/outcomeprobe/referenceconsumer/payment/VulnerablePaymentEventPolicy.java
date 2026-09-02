package org.outcomeprobe.referenceconsumer.payment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 취약 정책: 이벤트 중복 제거 없이 도착할 때마다 원장을 반영한다.
 *
 * <p>책임: 같은 eventId가 3번 도착하면 원장이 3번 반영되는 현실의 취약 구현을
 * 재현한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 정책은 의도적으로 중복을 막지 않는다.
 * OutcomeProbe Scenario에서 side effect count 실패를 보여주는 것이 목적이다.</p>
 */
@Component
@Profile("vulnerable")
public class VulnerablePaymentEventPolicy implements PaymentEventPolicy {

    private final PaymentRepository paymentRepository;

    /**
     * 결제 저장소를 주입한다.
     *
     * @param paymentRepository 결제 저장소
     */
    public VulnerablePaymentEventPolicy(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * eventId 확인 없이 성공 이벤트를 원장에 반영한다.
     *
     * @param paymentId 결제 식별자
     * @param eventId 이벤트 식별자
     */
    @Override
    public void handlePaymentSucceeded(String paymentId, String eventId) {
        // 취약 구현: 중복 eventId를 확인하지 않아 도착 횟수만큼 원장이 반영된다.
        paymentRepository.update(paymentId, Payment::withSuccessEventNoDedup);
    }
}
