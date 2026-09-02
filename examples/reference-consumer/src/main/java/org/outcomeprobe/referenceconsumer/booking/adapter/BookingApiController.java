package org.outcomeprobe.referenceconsumer.booking.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
import org.outcomeprobe.referenceconsumer.booking.Booking;
import org.outcomeprobe.referenceconsumer.booking.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 명령 trigger API다.
 *
 * <p>책임: OutcomeProbe Scenario의 target.http Step이 취소·일정 변경을 요청하는
 * 진입점을 제공한다.</p>
 */
@RestController
public class BookingApiController {

    private final BookingService bookingService;

    /**
     * 예약 서비스를 주입한다.
     *
     * @param bookingService 예약 업무 서비스
     */
    public BookingApiController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * 예약 취소를 접수한다.
     *
     * @param bookingId 예약 식별자
     * @return 처리 후 상태 요약
     */
    @PostMapping("/api/bookings/{bookingId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String bookingId) {
        Booking booking = bookingService.cancel(bookingId);
        return ResponseEntity.accepted().body(summary(booking));
    }

    /**
     * 예약 일정 변경을 접수한다.
     *
     * @param bookingId 예약 식별자
     * @param body startsAt을 담은 요청 body
     * @return 처리 후 상태 요약
     */
    @PostMapping("/api/bookings/{bookingId}/reschedule")
    public ResponseEntity<Map<String, Object>> reschedule(
            @PathVariable String bookingId,
            @RequestBody(required = false) Map<String, Object> body) {
        String startsAt = body == null ? "" : String.valueOf(body.getOrDefault("startsAt", ""));
        Booking booking = bookingService.reschedule(bookingId, startsAt);
        return ResponseEntity.accepted().body(summary(booking));
    }

    /**
     * 예약을 API 응답용 요약 map으로 변환한다.
     */
    private Map<String, Object> summary(Booking booking) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bookingId", booking.bookingId());
        result.put("status", booking.status().name());
        return result;
    }
}
