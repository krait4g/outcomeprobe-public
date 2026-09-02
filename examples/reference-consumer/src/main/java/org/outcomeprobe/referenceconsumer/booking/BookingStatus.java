package org.outcomeprobe.referenceconsumer.booking;

/**
 * Reference Consumer 내부의 예약 상태다.
 *
 * <p>책임: 예약 생명주기와 재조정 대기 상태를 표현한다.</p>
 * <p>핵심 불변식: RECONCILING은 명령 결과가 모호할 때 복원력 정책이 사용하는
 * 임시 상태며, Provider 조회로 실제 상태가 확정되면 벗어난다.</p>
 */
public enum BookingStatus {

    // 예약 생성 초기 상태다.
    CREATED,

    // 예약이 확정됐다.
    CONFIRMED,

    // 예약 시간이 변경됐다.
    RESCHEDULED,

    // 예약이 취소됐다.
    CANCELLED,

    // 명령 결과가 모호해 Provider 재조정이 필요하다.
    RECONCILING
}
