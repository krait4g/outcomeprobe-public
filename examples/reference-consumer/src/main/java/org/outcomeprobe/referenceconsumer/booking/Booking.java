package org.outcomeprobe.referenceconsumer.booking;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reference Consumer가 관리하는 예약 상태 snapshot이다.
 *
 * <p>책임: 외부 이벤트 version과 side effect 횟수를 함께 보관해 OutcomeProbe가
 * 역순·중복 이벤트의 영향을 판정할 수 있게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 객체는 in-memory 데모 상태다.
 * 취약 정책용 unsafe 적용과 복원력 정책용 guarded 적용을 모두 표현하며,
 * 어떤 정책을 쓸지는 호출하는 정책 객체가 결정한다.</p>
 *
 * @param bookingId 예약 식별자
 * @param status 현재 상태
 * @param version 마지막으로 반영한 외부 이벤트 version
 * @param openSlotCreateCount 취소로 생성한 open slot side effect 횟수
 * @param cancelEventProcessCount 취소 이벤트 처리 횟수
 * @param processedEventIds 처리 완료한 이벤트 ID 집합
 * @param needsReconciliation Provider 재조정 필요 여부
 */
public record Booking(
        String bookingId,
        BookingStatus status,
        long version,
        int openSlotCreateCount,
        int cancelEventProcessCount,
        Set<String> processedEventIds,
        boolean needsReconciliation) {

    /**
     * 새 예약의 초기 상태를 만든다.
     *
     * @param bookingId 예약 식별자
     * @return CONFIRMED 초기 예약
     */
    public static Booking initial(String bookingId) {
        return new Booking(bookingId, BookingStatus.CONFIRMED, 0, 0, 0, Set.of(), false);
    }

    /**
     * 이벤트를 중복·version 검증 없이 적용한다.
     *
     * <p>왜 위험한가: 도착 순서대로 상태를 덮어쓰고 취소 side effect를 매번
     * 실행해 역순·중복 이벤트에서 상태 회귀와 중복 side effect가 발생한다.</p>
     *
     * @param state 이벤트 상태
     * @param eventVersion 이벤트 version
     * @return 적용된 새 예약
     */
    public Booking applyEventUnsafe(BookingStatus state, long eventVersion) {
        int openSlotDelta = state == BookingStatus.CANCELLED ? 1 : 0;
        return new Booking(bookingId, state, eventVersion,
                openSlotCreateCount + openSlotDelta, cancelEventProcessCount + openSlotDelta,
                processedEventIds, false);
    }

    /**
     * eventId 중복 제거와 version guard로 이벤트를 적용한다.
     *
     * <p>이미 처리한 이벤트나 현재보다 낮은 version은 무시한다. 취소 side effect는
     * 처음 CANCELLED로 전이될 때만 1회 발생한다.</p>
     *
     * @param eventId 이벤트 식별자
     * @param state 이벤트 상태
     * @param eventVersion 이벤트 version
     * @return 적용됐으면 새 예약, 무시됐으면 empty
     */
    public Optional<Booking> applyEventGuarded(String eventId, BookingStatus state, long eventVersion) {
        if (processedEventIds.contains(eventId)) {
            return Optional.empty();
        }
        Set<String> events = new LinkedHashSet<>(processedEventIds);
        events.add(eventId);
        // 낮은 version 이벤트는 최신 상태를 회귀시키지 않도록 무시한다.
        if (eventVersion <= version) {
            return Optional.of(new Booking(bookingId, status, version,
                    openSlotCreateCount, cancelEventProcessCount, Set.copyOf(events),
                    needsReconciliation));
        }
        boolean firstCancellation = state == BookingStatus.CANCELLED && status != BookingStatus.CANCELLED;
        int openSlotDelta = firstCancellation ? 1 : 0;
        return Optional.of(new Booking(bookingId, state, eventVersion,
                openSlotCreateCount + openSlotDelta, cancelEventProcessCount + openSlotDelta,
                Set.copyOf(events), false));
    }

    /**
     * Provider 재조정이 필요하다고 표시한다.
     *
     * @return 표시된 새 예약
     */
    public Booking markReconciliationNeeded() {
        return new Booking(bookingId, status, version, openSlotCreateCount,
                cancelEventProcessCount, processedEventIds, true);
    }

    /**
     * Provider 조회 결과로 상태를 재조정한다.
     *
     * <p>재조정으로 처음 CANCELLED를 확정하면 취소 side effect를 1회 반영한다.
     * Callback이 누락돼도 후속 업무가 정확히 한 번 발생해야 하기 때문이다.</p>
     *
     * @param providerState Provider가 노출한 상태
     * @param providerVersion Provider resource version
     * @return 재조정된 새 예약
     */
    public Booking reconciledWith(BookingStatus providerState, long providerVersion) {
        boolean firstCancellation = providerState == BookingStatus.CANCELLED
                && status != BookingStatus.CANCELLED;
        int openSlotDelta = firstCancellation ? 1 : 0;
        return new Booking(bookingId, providerState, Math.max(version, providerVersion),
                openSlotCreateCount + openSlotDelta, cancelEventProcessCount + openSlotDelta,
                processedEventIds, false);
    }
}
