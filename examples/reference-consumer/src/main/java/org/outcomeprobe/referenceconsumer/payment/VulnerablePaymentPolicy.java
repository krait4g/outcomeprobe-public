package org.outcomeprobe.referenceconsumer.payment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 취약 정책: Provider timeout을 즉시 결제 실패로 확정한다.
 *
 * <p>책임: 현실에서 자주 볼 수 있는 "timeout = 실패" 처리를 재현한다.
 * Provider 내부 결제가 이미 SUCCEEDED로 commit됐어도 이 정책은 FAILED로
 * 확정해 내부·외부 상태 불일치를 만든다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 정책은 의도적으로 복원력 메커니즘을
 * 갖추지 않았다. OutcomeProbe Scenario에서 FAIL을 보여주는 것이 목적이다.</p>
 */
@Component
@Profile("vulnerable")
public class VulnerablePaymentPolicy implements AmbiguousPaymentPolicy {

    private final PaymentRepository paymentRepository;

    /**
     * 상태 전이에 사용할 저장소를 주입한다.
     *
     * @param paymentRepository 결제 저장소
     */
    public VulnerablePaymentPolicy(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * 모호한 결과를 즉시 FAILED로 확정한다.
     *
     * <p>왜 문제인가: Provider는 commit했을 수 있다. 조회 없이 실패로 확정하면
     * 재시도 시 중복 승인 또는 영구 불일치가 발생한다.</p>
     *
     * @param paymentId 결제 식별자
     */
    @Override
    public void handleAmbiguousConfirm(String paymentId) {
        // 취약 구현: Provider 조회 없이 timeout을 즉시 실패로 확정한다.
        paymentRepository.update(paymentId, payment -> payment.transitionTo(PaymentStatus.FAILED));
    }

    /**
     * 모호한 환불 결과를 즉시 FAILED로 확정한다.
     *
     * <p>왜 문제인가: Provider는 이미 환불을 commit했을 수 있다. 조회 없이
     * 실패로 확정하면 내부 상태가 Provider truth와 영구히 어긋난다.</p>
     *
     * @param paymentId 결제 식별자
     */
    @Override
    public void handleAmbiguousRefund(String paymentId) {
        // 취약 구현: 환불 timeout을 즉시 실패로 확정한다.
        paymentRepository.update(paymentId, Payment::markRefundFailed);
    }

    /**
     * 이 정책은 background 복구를 수행하지 않는다.
     *
     * @return 항상 false
     */
    @Override
    public boolean performsBackgroundRecovery() {
        return false;
    }
}
