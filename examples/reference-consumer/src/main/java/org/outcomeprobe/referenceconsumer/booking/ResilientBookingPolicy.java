package org.outcomeprobe.referenceconsumer.booking;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 복원력 예약 정책: version guard·중복 제거·Provider 재조정으로 수렴한다.
 *
 * <p>책임: 역순 이벤트의 상태 회귀와 중복 side effect를 막고, 모호한 명령
 * 결과는 Provider 조회로 재조정한다.</p>
 * <p>핵심 불변식: 낮은 version 이벤트는 상태를 바꾸지 못하며, 취소 side effect는
 * 최초 CANCELLED 전이에서만 1회 발생한다.</p>
 */
@Component
@Profile("resilient")
public class ResilientBookingPolicy implements BookingEventPolicy {

    private final BookingStore bookingStore;
    private final BookingProviderClient providerClient;
    private final ObjectMapper objectMapper;

    /**
     * 재조정에 필요한 협업 객체를 주입한다.
     *
     * @param bookingStore 예약 저장소
     * @param providerClient Provider 조회 client
     * @param objectMapper 응답 해석 mapper
     */
    public ResilientBookingPolicy(BookingStore bookingStore, BookingProviderClient providerClient,
                                  ObjectMapper objectMapper) {
        this.bookingStore = bookingStore;
        this.providerClient = providerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 중복 제거와 version guard로 이벤트를 적용한다.
     *
     * @param bookingId 예약 식별자
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param version 이벤트 version
     */
    @Override
    public void handleBookingEvent(String bookingId, String eventId, BookingStatus state, long version) {
        bookingStore.findOrCreate(bookingId);
        bookingStore.update(bookingId, booking ->
                booking.applyEventGuarded(eventId, state, version).orElse(booking));
    }

    /**
     * 모호한 명령 결과를 재조정 대상으로 표시한다.
     *
     * @param bookingId 예약 식별자
     */
    @Override
    public void handleAmbiguousCommand(String bookingId) {
        bookingStore.findOrCreate(bookingId);
        bookingStore.update(bookingId, Booking::markReconciliationNeeded);
    }

    /**
     * 재조정 표시된 예약을 Provider 조회로 수렴시킨다.
     *
     * <p>왜 주기 조회인가: Callback이 생략·지연돼도 Provider truth로 최종
     * 상태를 확정할 수 있어야 하기 때문이다.</p>
     */
    @Scheduled(fixedDelay = 300L)
    public void reconcile() {
        for (Booking booking : bookingStore.findAll()) {
            if (!booking.needsReconciliation()) {
                continue;
            }
            BookingProviderClient.CallResult result = providerClient.getBooking(booking.bookingId());
            if (!result.isSuccess() || result.body() == null) {
                continue;
            }
            try {
                JsonNode body = objectMapper.readTree(result.body());
                BookingStatus providerState =
                        BookingStatus.valueOf(body.path("status").asText("CONFIRMED"));
                long providerVersion = body.path("version").asLong(0);
                bookingStore.update(booking.bookingId(),
                        current -> current.reconciledWith(providerState, providerVersion));
            } catch (RuntimeException e) {
                // 응답 해석 실패는 다음 재조정 주기에 다시 시도한다.
            }
        }
    }
}
