package org.outcomeprobe.referenceconsumer.booking;

/**
 * 예약 Webhook 이벤트와 모호한 명령 결과를 처리하는 정책이다.
 *
 * <p>책임: 취약 정책과 복원력 정책의 차이를 전략 객체로 분리한다.</p>
 */
public interface BookingEventPolicy {

    /**
     * 예약 상태 변경 이벤트를 처리한다.
     *
     * @param bookingId 예약 식별자
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param version 이벤트 version
     */
    void handleBookingEvent(String bookingId, String eventId, BookingStatus state, long version);

    /**
     * Provider 명령 결과가 모호할 때 처리한다.
     *
     * @param bookingId 예약 식별자
     */
    void handleAmbiguousCommand(String bookingId);
}
