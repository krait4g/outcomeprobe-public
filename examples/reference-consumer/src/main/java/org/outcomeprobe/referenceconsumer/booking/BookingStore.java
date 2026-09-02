package org.outcomeprobe.referenceconsumer.booking;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Repository;

/**
 * in-memory 예약 저장소다.
 *
 * <p>책임: 데모 예약 상태를 보관하고 compute 기반 원자 갱신을 제공한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 프로세스 재시작 시 상태가 사라진다.
 * OutcomeProbe Scenario는 항상 reset으로 시작하므로 데모에 충분하다.</p>
 */
@Repository
public class BookingStore {

    private final ConcurrentHashMap<String, Booking> store = new ConcurrentHashMap<>();

    /**
     * 빈 저장소를 생성한다.
     */
    public BookingStore() {
    }

    /**
     * 예약을 조회한다.
     *
     * @param bookingId 예약 식별자
     * @return 존재하면 예약
     */
    public Optional<Booking> findById(String bookingId) {
        return Optional.ofNullable(store.get(bookingId));
    }

    /**
     * 없으면 초기 예약을 생성한다.
     *
     * @param bookingId 예약 식별자
     * @return 기존 또는 생성된 예약
     */
    public Booking findOrCreate(String bookingId) {
        return store.computeIfAbsent(bookingId, Booking::initial);
    }

    /**
     * 원자 변환으로 예약을 갱신한다.
     *
     * @param bookingId 예약 식별자
     * @param updater 변환 함수
     */
    public void update(String bookingId, UnaryOperator<Booking> updater) {
        // compute 안의 변환은 동일 key에 대해 직렬 실행돼 중복 side effect를 막는다.
        store.computeIfPresent(bookingId, (key, booking) -> updater.apply(booking));
    }

    /**
     * 모든 예약을 반환한다.
     *
     * @return 예약 컬렉션
     */
    public Collection<Booking> findAll() {
        return store.values();
    }

    /**
     * 예약을 삭제한다.
     *
     * @param bookingId 예약 식별자
     */
    public void delete(String bookingId) {
        store.remove(bookingId);
    }
}
