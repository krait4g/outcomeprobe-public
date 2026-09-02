package org.outcomeprobe.referenceconsumer.payment;

/**
 * Provider 결과가 모호할 때 애플리케이션이 취할 복구 정책을 정의한다.
 *
 * <p>책임: 취약 정책과 복원력 정책의 차이를 조건문이 아니라 전략 객체로
 * 분리한다. 같은 Scenario에서 두 정책의 최종 상태 차이가 OutcomeProbe로 검증된다.</p>
 * <p>핵심 불변식: 구현체는 timeout을 상태 불명으로 유지할지 즉시 실패로 확정할지
 * 스스로 결정한다. OutcomeProbe는 이 선택의 결과만 판정한다.</p>
 */
public interface AmbiguousPaymentPolicy {

    /**
     * Provider 승인 결과가 모호할 때 처리한다.
     *
     * @param paymentId 결제 식별자
     */
    void handleAmbiguousConfirm(String paymentId);

    /**
     * Provider 환불 결과가 모호할 때 처리한다.
     *
     * @param paymentId 결제 식별자
     */
    void handleAmbiguousRefund(String paymentId);

    /**
     * 이 정책이 background 복구(Provider 조회)를 수행하는지 여부다.
     *
     * @return 복구 수행 시 true
     */
    boolean performsBackgroundRecovery();
}
