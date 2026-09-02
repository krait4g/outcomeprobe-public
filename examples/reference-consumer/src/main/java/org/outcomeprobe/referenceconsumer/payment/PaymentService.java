package org.outcomeprobe.referenceconsumer.payment;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reference Consumer의 결제 업무 흐름을 조정한다.
 *
 * <p>책임: 승인 trigger 처리와 모호한 결과의 정책 위임을 담당한다. 취약·복원력
 * 차이는 {@link AmbiguousPaymentPolicy} 구현체로만 표현되고 이 서비스에는
 * profile 조건문이 등장하지 않는다.</p>
 * <p>핵심 불변식: Provider 2xx 응답만 즉시 성공으로 확정한다. 그 외 결과는
 * 정책이 처리하며, 성공 확정은 원장 1회 반영과 원자적으로 일어난다.</p>
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ProviderPaymentClient providerClient;
    private final AmbiguousPaymentPolicy ambiguousPaymentPolicy;

    /**
     * 결제 처리에 필요한 협업 객체를 주입한다.
     *
     * @param paymentRepository 결제 저장소
     * @param providerClient Provider 호출 client
     * @param ambiguousPaymentPolicy 활성 profile의 모호한 결과 정책
     */
    public PaymentService(PaymentRepository paymentRepository,
                          ProviderPaymentClient providerClient,
                          AmbiguousPaymentPolicy ambiguousPaymentPolicy) {
        this.paymentRepository = paymentRepository;
        this.providerClient = providerClient;
        this.ambiguousPaymentPolicy = ambiguousPaymentPolicy;
    }

    /**
     * Provider 승인을 요청하고 결과에 따라 상태를 전이한다.
     *
     * <p>실패 시 보존하는 것: Provider 응답이 모호하면 정책에 따라 상태를
     * 보류하거나 실패로 확정하며, 어떤 경우에도 원장은 성공 확정 전 반영되지 않는다.</p>
     *
     * @param paymentId 결제 식별자
     * @param amount 승인 금액
     * @param idempotencyKey 멱등 키
     * @return 처리 후 결제 상태
     */
    public Payment confirm(String paymentId, long amount, String idempotencyKey) {
        Payment payment = paymentRepository.findOrCreate(paymentId, amount);

        // 이미 성공 확정된 결제의 중복 승인은 side effect 없이 현재 상태를 반환한다.
        if (payment.status() == PaymentStatus.SUCCEEDED) {
            return payment;
        }

        paymentRepository.update(paymentId,
                current -> current.status() == PaymentStatus.CREATED
                        ? current.transitionTo(PaymentStatus.CONFIRMING)
                        : current);

        ProviderPaymentClient.ProviderCallResult result =
                providerClient.confirm(paymentId, amount, idempotencyKey);

        if (result.isSuccess()) {
            // Provider 성공 응답을 받은 경우에만 원장을 1회 반영한다.
            paymentRepository.update(paymentId, Payment::finalizeSucceeded);
        } else {
            ambiguousPaymentPolicy.handleAmbiguousConfirm(paymentId);
        }
        return paymentRepository.findById(paymentId).orElse(payment);
    }

    /**
     * Provider 환불을 요청하고 결과에 따라 상태를 전이한다.
     *
     * <p>실패 시 보존하는 것: 응답이 모호하면 정책에 위임한다. 취약 정책은 즉시
     * FAILED로 확정하고, 복원력 정책은 REFUNDING을 유지한 뒤 조회로 수렴한다.</p>
     *
     * @param paymentId 결제 식별자
     * @param idempotencyKey 멱등 키
     * @return 처리 후 결제 상태
     */
    public Payment refund(String paymentId, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            throw new IllegalStateException("존재하지 않는 결제입니다: " + paymentId);
        }
        if (payment.status() == PaymentStatus.REFUNDED) {
            return payment;
        }

        // 환불 외부 호출 횟수를 기록해 재시도 폭주를 검출할 수 있게 한다.
        paymentRepository.update(paymentId, Payment::startRefund);

        ProviderPaymentClient.ProviderCallResult result = providerClient.refund(paymentId, idempotencyKey);

        if (result.isSuccess()) {
            paymentRepository.update(paymentId, Payment::finalizeRefunded);
        } else {
            ambiguousPaymentPolicy.handleAmbiguousRefund(paymentId);
        }
        return paymentRepository.findById(paymentId).orElse(payment);
    }

    /**
     * 결제를 조회한다.
     *
     * @param paymentId 결제 식별자
     * @return 존재하면 결제
     */
    public Optional<Payment> find(String paymentId) {
        return paymentRepository.findById(paymentId);
    }

    /**
     * 결제를 초기화한다.
     *
     * @param paymentId 결제 식별자
     */
    public void reset(String paymentId) {
        paymentRepository.delete(paymentId);
    }

    /**
     * Scenario 시작 조건에 맞는 초기 상태 결제를 생성한다.
     *
     * <p>환불 Scenario처럼 "이미 성공한 결제"에서 시작해야 할 때 사용한다.
     * SUCCEEDED 생성은 원장 1회 반영을 포함한다.</p>
     *
     * @param paymentId 결제 식별자
     * @param amount 금액
     * @param initialStatus 초기 상태 문자열
     */
    public void seedInitial(String paymentId, long amount, String initialStatus) {
        paymentRepository.findOrCreate(paymentId, amount);
        if ("SUCCEEDED".equals(initialStatus)) {
            paymentRepository.update(paymentId, Payment::finalizeSucceeded);
        } else {
            PaymentStatus status = PaymentStatus.valueOf(initialStatus);
            paymentRepository.update(paymentId, payment -> payment.transitionTo(status));
        }
    }
}
