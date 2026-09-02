package org.outcomeprobe.referenceconsumer.payment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 복원력 정책: Provider timeout을 UNKNOWN으로 유지하고 background 조회로 수렴한다.
 *
 * <p>책임: 모호한 결과를 즉시 확정하지 않고 Provider 상태 조회로 실제 상태를
 * 확인한 뒤, 원장 반영을 정확히 1회 수행해 최종 SUCCEEDED로 수렴시킨다.</p>
 * <p>핵심 불변식: ledger 반영은 상태 전이와 원자적으로 일어나 중복 side effect가
 * 발생하지 않는다. UNKNOWN → SUCCEEDED 전이는 조회 결과가 SUCCEEDED일 때만 일어난다.</p>
 */
@Component
@Profile("resilient")
public class ResilientPaymentPolicy implements AmbiguousPaymentPolicy {

    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentPolicy.class);

    private final PaymentRepository paymentRepository;
    private final ProviderPaymentClient providerClient;
    private final ObjectMapper objectMapper;

    /**
     * 복구 loop에 필요한 협업 객체를 주입한다.
     *
     * @param paymentRepository 결제 저장소
     * @param providerClient Provider 조회 client
     * @param objectMapper 응답 해석 mapper
     */
    public ResilientPaymentPolicy(PaymentRepository paymentRepository,
                                  ProviderPaymentClient providerClient,
                                  ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.providerClient = providerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 모호한 결과를 UNKNOWN으로 표시해 확정 판단을 보류한다.
     *
     * @param paymentId 결제 식별자
     */
    @Override
    public void handleAmbiguousConfirm(String paymentId) {
        // 복원력 구현: timeout을 상태 불명으로 유지하고 background 조회로 수렴한다.
        paymentRepository.update(paymentId, payment -> payment.transitionTo(PaymentStatus.UNKNOWN));
    }

    /**
     * 모호한 환불 결과는 REFUNDING을 유지해 background 조회로 수렴시킨다.
     *
     * @param paymentId 결제 식별자
     */
    @Override
    public void handleAmbiguousRefund(String paymentId) {
        // 복원력 구현: startRefund가 이미 REFUNDING으로 표시했다. 조회로 수렴한다.
    }

    /**
     * 이 정책은 background 복구를 수행한다.
     *
     * @return 항상 true
     */
    @Override
    public boolean performsBackgroundRecovery() {
        return true;
    }

    /**
     * UNKNOWN·REFUNDING 결제를 주기적으로 Provider에 조회해 실제 상태로 수렴시킨다.
     *
     * <p>왜 반복 조회인가: Callback이 생략된 Scenario에서 최종 상태를 확정하는
     * 유일한 경로가 Provider 조회이기 때문이다.</p>
     */
    @Scheduled(fixedDelay = 300L)
    public void recoverUnknownPayments() {
        for (Payment payment : paymentRepository.findAll()) {
            if (payment.status() != PaymentStatus.UNKNOWN && payment.status() != PaymentStatus.REFUNDING) {
                continue;
            }
            ProviderPaymentClient.ProviderCallResult result = providerClient.getPayment(payment.paymentId());
            // 조회 횟수는 side effect 검증을 위해 항상 기록한다.
            paymentRepository.update(payment.paymentId(), Payment::withQueryCounted);
            if (!result.isSuccess() || result.body() == null) {
                continue;
            }
            try {
                JsonNode body = objectMapper.readTree(result.body());
                String providerState = body.path("status").asText("");
                switch (providerState) {
                    case "SUCCEEDED" -> {
                        // 환불 중에는 Provider의 선행 승인 상태가 잠시 보일 수 있으므로 다음 조회를 기다린다.
                        paymentRepository.update(payment.paymentId(), current -> {
                            if (current.status() == PaymentStatus.UNKNOWN) {
                                return current.finalizeSucceeded();
                            }
                            return current;
                        });
                    }
                    case "REFUNDED" ->
                        paymentRepository.update(payment.paymentId(), Payment::finalizeRefunded);
                    case "FAILED" ->
                        paymentRepository.update(payment.paymentId(),
                                current -> current.transitionTo(PaymentStatus.FAILED));
                    default -> log.debug("아직 수렴하지 않은 provider 상태: {}", providerState);
                }
            } catch (Exception e) {
                log.debug("provider 응답 해석 실패: {}", e.getMessage());
            }
        }
    }
}
