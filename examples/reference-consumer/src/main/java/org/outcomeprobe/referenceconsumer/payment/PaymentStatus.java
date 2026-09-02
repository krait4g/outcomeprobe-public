package org.outcomeprobe.referenceconsumer.payment;

/**
 * Reference Consumer 내부의 결제 상태다.
 *
 * <p>책임: Provider 응답이 모호할 때 취약 정책과 복원력 정책이 서로 다른
 * 상태로 분기하는 것을 표현한다.</p>
 * <p>핵심 불변식: UNKNOWN·REFUNDING은 확정 전 임시 상태다. 복원력 정책은 이
 * 상태를 Provider 조회로 수렴시키고, 취약 정책은 즉시 FAILED로 확정한다.</p>
 */
public enum PaymentStatus {

    // 결제가 생성된 초기 상태다.
    CREATED,

    // Provider 승인 요청을 진행 중이다.
    CONFIRMING,

    // 승인 결과가 모호해 확정을 보류한 상태다.
    UNKNOWN,

    // 최종 성공으로 수렴했다.
    SUCCEEDED,

    // 최종 실패로 확정됐다.
    FAILED,

    // 환불 요청을 보냈고 결과가 모호한 상태다.
    REFUNDING,

    // 최종 환불 완료로 수렴했다.
    REFUNDED
}
