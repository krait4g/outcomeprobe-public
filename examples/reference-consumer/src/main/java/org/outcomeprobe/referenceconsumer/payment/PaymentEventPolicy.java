package org.outcomeprobe.referenceconsumer.payment;

/**
 * 결제 성공 Webhook 이벤트를 처리하는 정책이다.
 *
 * <p>책임: 동일 이벤트를 취약 정책과 복원력 정책이 어떻게 다르게 처리하는지
 * 전략 객체로 표현한다. 조건문 분기가 코드 전체에 퍼지지 않게 한다.</p>
 * <p>핵심 불변식: 구현체는 중복 side effect의 발생 여부를 스스로 결정한다.
 * OutcomeProbe는 그 결과의 차이를 최종 상태로 판정한다.</p>
 */
public interface PaymentEventPolicy {

    /**
     * 결제 성공 이벤트를 처리한다.
     *
     * @param paymentId 결제 식별자
     * @param eventId 이벤트 식별자
     */
    void handlePaymentSucceeded(String paymentId, String eventId);
}
