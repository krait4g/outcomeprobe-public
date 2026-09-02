package org.outcomeprobe.referenceconsumer.booking;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 취약 예약 정책: 도착 순서대로 덮어쓰고 side effect를 매번 실행한다.
 *
 * <p>책임: last-write-wins와 중복 side effect를 재현한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 정책은 의도적으로 version guard와
 * 중복 제거가 없다. 역순·중복 Scenario에서 FAIL을 보여주는 것이 목적이다.</p>
 */
@Component
@Profile("vulnerable")
public class VulnerableBookingPolicy implements BookingEventPolicy {

    private final BookingStore bookingStore;

    /**
     * 예약 저장소를 주입한다.
     *
     * @param bookingStore 예약 저장소
     */
    public VulnerableBookingPolicy(BookingStore bookingStore) {
        this.bookingStore = bookingStore;
    }

    /**
     * version·중복 확인 없이 이벤트를 적용한다.
     *
     * @param bookingId 예약 식별자
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param version 이벤트 version
     */
    @Override
    public void handleBookingEvent(String bookingId, String eventId, BookingStatus state, long version) {
        bookingStore.findOrCreate(bookingId);
        // 취약 구현: 낮은 version 이벤트가 최신 상태를 덮어쓸 수 있다.
        bookingStore.update(bookingId, booking -> booking.applyEventUnsafe(state, version));
    }

    /**
     * 모호한 명령 결과를 무시해 재조정 없이 상태를 고정한다.
     *
     * @param bookingId 예약 식별자
     */
    @Override
    public void handleAmbiguousCommand(String bookingId) {
        // 취약 구현: 재조정 loop가 없어 모호한 상태가 최종 상태가 된다.
    }
}
