package org.outcomeprobe.referenceconsumer.booking;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 예약 명령 흐름을 조정한다.
 *
 * <p>책임: 취소·일정 변경 trigger를 Provider에 전달하고, 결과가 모호하면
 * 정책에 위임한다.</p>
 */
@Service
public class BookingService {

    private final BookingStore bookingStore;
    private final BookingProviderClient providerClient;
    private final BookingEventPolicy policy;

    /**
     * 예약 처리 협업 객체를 주입한다.
     *
     * @param bookingStore 예약 저장소
     * @param providerClient Provider client
     * @param policy 활성 profile의 이벤트 정책
     */
    public BookingService(BookingStore bookingStore, BookingProviderClient providerClient,
                          BookingEventPolicy policy) {
        this.bookingStore = bookingStore;
        this.providerClient = providerClient;
        this.policy = policy;
    }

    /**
     * Provider 취소를 요청하고 결과를 처리한다.
     *
     * @param bookingId 예약 식별자
     * @return 처리 후 예약
     */
    public Booking cancel(String bookingId) {
        bookingStore.findOrCreate(bookingId);
        BookingProviderClient.CallResult result = providerClient.cancel(bookingId);
        if (result.isSuccess()) {
            bookingStore.update(bookingId, booking ->
                    booking.applyEventUnsafe(BookingStatus.CANCELLED, booking.version()));
        } else {
            policy.handleAmbiguousCommand(bookingId);
        }
        return bookingStore.findById(bookingId).orElseThrow();
    }

    /**
     * Provider 일정 변경을 요청하고 결과를 처리한다.
     *
     * @param bookingId 예약 식별자
     * @param startsAt 변경 시작 시각
     * @return 처리 후 예약
     */
    public Booking reschedule(String bookingId, String startsAt) {
        bookingStore.findOrCreate(bookingId);
        BookingProviderClient.CallResult result = providerClient.reschedule(bookingId, startsAt);
        if (result.isSuccess()) {
            bookingStore.update(bookingId, booking ->
                    booking.applyEventUnsafe(BookingStatus.RESCHEDULED, booking.version()));
        } else {
            policy.handleAmbiguousCommand(bookingId);
        }
        return bookingStore.findById(bookingId).orElseThrow();
    }

    /**
     * 예약을 조회한다.
     *
     * @param bookingId 예약 식별자
     * @return 존재하면 예약
     */
    public Optional<Booking> find(String bookingId) {
        return bookingStore.findById(bookingId);
    }

    /**
     * 예약을 초기화한다.
     *
     * @param bookingId 예약 식별자
     */
    public void reset(String bookingId) {
        bookingStore.delete(bookingId);
    }
}
