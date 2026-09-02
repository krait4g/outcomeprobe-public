package org.outcomeprobe.referenceconsumer.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.outcomeprobe.referenceconsumer.booking.Booking;
import org.outcomeprobe.referenceconsumer.booking.BookingService;
import org.outcomeprobe.referenceconsumer.generic.GenericEntity;
import org.outcomeprobe.referenceconsumer.generic.GenericEntityStore;
import org.outcomeprobe.referenceconsumer.payment.Payment;
import org.outcomeprobe.referenceconsumer.payment.PaymentService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OutcomeProbe Probe·초기화 전용 read/support API다.
 *
 * <p>책임: Scenario가 Target을 초기화하고 최종 상태·side effect 횟수를 외부에서
 * 관측할 수 있게 한다. demo profile에서만 bean으로 등록된다.</p>
 * <p>핵심 불변식: 이 endpoint는 production profile에서 존재하지 않는다.
 * 따라서 데모용 초기화 경로가 운영 구성에 노출되지 않는다.</p>
 */
@RestController
@Profile("demo")
public class TestSupportController {

    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final GenericEntityStore entityStore;

    /**
     * test-support 협업 객체를 주입한다.
     *
     * @param paymentService 결제 서비스
     * @param bookingService 예약 서비스
     * @param entityStore generic 엔티티 저장소
     */
    public TestSupportController(PaymentService paymentService, BookingService bookingService,
                                 GenericEntityStore entityStore) {
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.entityStore = entityStore;
    }

    /**
     * 결제·예약·엔티티를 초기화한다.
     *
     * <p>요청 body의 paymentId·bookingId·entityId 중 존재하는 key만 초기화한다.
     * entityId가 있으면 READY 상태로 생성해 서명 거부 검증의 기준 상태로 쓴다.</p>
     *
     * @param body 초기화 대상 식별자
     * @return 204
     */
    @PostMapping("/test-support/reset")
    public ResponseEntity<Void> reset(@RequestBody Map<String, Object> body) {
        Object paymentId = body.get("paymentId");
        if (paymentId != null) {
            paymentService.reset(String.valueOf(paymentId));
            // 환불 Scenario처럼 이미 성공한 결제에서 시작해야 하면 초기 상태를 생성한다.
            Object initialStatus = body.get("initialStatus");
            if (initialStatus != null) {
                long amount = body.get("amount") instanceof Number number ? number.longValue() : 0L;
                paymentService.seedInitial(String.valueOf(paymentId), amount,
                        String.valueOf(initialStatus));
            }
        }
        Object bookingId = body.get("bookingId");
        if (bookingId != null) {
            bookingService.reset(String.valueOf(bookingId));
        }
        Object entityId = body.get("entityId");
        if (entityId != null) {
            entityStore.delete(String.valueOf(entityId));
            entityStore.findOrCreate(String.valueOf(entityId));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 결제 상태와 side effect 횟수를 노출한다.
     *
     * @param paymentId 결제 식별자
     * @return 상태 문서 또는 404
     */
    @GetMapping("/test-support/payments/{paymentId}")
    public ResponseEntity<Map<String, Object>> paymentState(@PathVariable String paymentId) {
        return paymentService.find(paymentId)
                .map(state -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("paymentId", state.paymentId());
                    result.put("status", state.status().name());
                    result.put("amount", state.amount());
                    result.put("version", state.version());
                    result.put("providerQueryCount", state.providerQueryCount());
                    result.put("ledgerPostCount", state.ledgerPostCount());
                    result.put("refundRequestCount", state.refundRequestCount());
                    result.put("processedEventCount", state.processedEventIds().size());
                    result.put("processedEventIds", state.processedEventIds().stream().sorted().toList());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 예약 상태와 side effect 횟수를 노출한다.
     *
     * @param bookingId 예약 식별자
     * @return 상태 문서 또는 404
     */
    @GetMapping("/test-support/bookings/{bookingId}")
    public ResponseEntity<Map<String, Object>> bookingState(@PathVariable String bookingId) {
        return bookingService.find(bookingId)
                .map(state -> ResponseEntity.ok(bookingSummary(state)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * generic 엔티티 상태를 노출한다.
     *
     * @param entityId 엔티티 식별자
     * @return 상태 문서 또는 404
     */
    @GetMapping("/test-support/entities/{entityId}")
    public ResponseEntity<Map<String, Object>> entityState(@PathVariable String entityId) {
        return entityStore.findById(entityId)
                .map(entity -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("entityId", entity.entityId());
                    result.put("state", entity.state());
                    result.put("version", entity.version());
                    result.put("processedEventCount", entity.processedEventCount());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 다음 N회 generic Webhook 요청을 503으로 응답하게 설정한다.
     *
     * <p>retry Scenario가 Target 일시 실패를 재현하는 데 사용한다.</p>
     *
     * @param entityId 엔티티 식별자
     * @param body {count: N}
     * @return 204
     */
    @PostMapping("/test-support/entities/{entityId}/fail-next")
    public ResponseEntity<Void> failNext(@PathVariable String entityId,
                                         @RequestBody Map<String, Object> body) {
        Object count = body.get("count");
        int times = count instanceof Number number ? number.intValue() : 0;
        entityStore.findOrCreate(entityId);
        entityStore.failNext(entityId, times);
        return ResponseEntity.noContent().build();
    }

    /**
     * 예약 상태를 Probe 응답용 map으로 변환한다. side effect 횟수를 포함한다.
     */
    private Map<String, Object> bookingSummary(Booking state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bookingId", state.bookingId());
        result.put("status", state.status().name());
        result.put("version", state.version());
        result.put("openSlotCreateCount", state.openSlotCreateCount());
        result.put("cancelEventProcessCount", state.cancelEventProcessCount());
        result.put("processedEventCount", state.processedEventIds().size());
        result.put("needsReconciliation", state.needsReconciliation());
        return result;
    }
}
