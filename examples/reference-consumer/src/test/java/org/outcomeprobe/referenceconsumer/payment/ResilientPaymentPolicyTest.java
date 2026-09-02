package org.outcomeprobe.referenceconsumer.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 복원력 결제 정책의 Provider 조회 수렴 계약을 검증한다.
 */
class ResilientPaymentPolicyTest {

    /**
     * 환불 반영 전의 선행 SUCCEEDED 조회는 REFUNDING을 후퇴시키지 않아야 한다.
     */
    @Test
    void refundWaitsWhenProviderStillReportsSucceeded() {
        InMemoryPaymentRepository repository = refundingPayment("payment-refund");
        ProviderPaymentClient providerClient = mock(ProviderPaymentClient.class);
        when(providerClient.getPayment("payment-refund"))
                .thenReturn(successfulProviderState("SUCCEEDED"))
                .thenReturn(successfulProviderState("REFUNDED"));
        ResilientPaymentPolicy policy = new ResilientPaymentPolicy(
                repository, providerClient, new ObjectMapper());

        policy.recoverUnknownPayments();

        Payment waiting = repository.findById("payment-refund").orElseThrow();
        assertThat(waiting.status()).isEqualTo(PaymentStatus.REFUNDING);
        assertThat(waiting.providerQueryCount()).isEqualTo(1);
        assertThat(waiting.ledgerPostCount()).isEqualTo(1);
        assertThat(waiting.refundRequestCount()).isEqualTo(1);

        policy.recoverUnknownPayments();

        Payment refunded = repository.findById("payment-refund").orElseThrow();
        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.providerQueryCount()).isEqualTo(2);
        assertThat(refunded.ledgerPostCount()).isEqualTo(1);
        assertThat(refunded.refundRequestCount()).isEqualTo(1);
    }

    /**
     * 승인 결과가 모호했던 UNKNOWN 결제는 Provider SUCCEEDED에 수렴해야 한다.
     */
    @Test
    void unknownPaymentStillConvergesToSucceeded() {
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.findOrCreate("payment-confirm", 100L);
        repository.update("payment-confirm", payment -> payment.transitionTo(PaymentStatus.UNKNOWN));
        ProviderPaymentClient providerClient = mock(ProviderPaymentClient.class);
        when(providerClient.getPayment("payment-confirm"))
                .thenReturn(successfulProviderState("SUCCEEDED"));
        ResilientPaymentPolicy policy = new ResilientPaymentPolicy(
                repository, providerClient, new ObjectMapper());

        policy.recoverUnknownPayments();

        Payment succeeded = repository.findById("payment-confirm").orElseThrow();
        assertThat(succeeded.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(succeeded.providerQueryCount()).isEqualTo(1);
        assertThat(succeeded.ledgerPostCount()).isEqualTo(1);
    }

    /**
     * 환불 중인 결제 fixture를 만든다.
     *
     * @param paymentId 결제 식별자
     * @return REFUNDING 상태가 저장된 저장소
     */
    private InMemoryPaymentRepository refundingPayment(String paymentId) {
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.findOrCreate(paymentId, 100L);
        repository.update(paymentId, Payment::finalizeSucceeded);
        repository.update(paymentId, Payment::startRefund);
        return repository;
    }

    /**
     * Provider의 성공 조회 결과를 만든다.
     *
     * @param status Provider 결제 상태
     * @return HTTP 200 조회 결과
     */
    private ProviderPaymentClient.ProviderCallResult successfulProviderState(String status) {
        return new ProviderPaymentClient.ProviderCallResult(
                200, "{\"status\":\"" + status + "\"}", null);
    }
}
