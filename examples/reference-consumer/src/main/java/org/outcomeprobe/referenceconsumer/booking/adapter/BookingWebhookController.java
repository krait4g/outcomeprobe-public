package org.outcomeprobe.referenceconsumer.booking.adapter;

import java.nio.charset.StandardCharsets;
import org.outcomeprobe.referenceconsumer.booking.BookingEventPolicy;
import org.outcomeprobe.referenceconsumer.booking.BookingStatus;
import org.outcomeprobe.referenceconsumer.support.WebhookSignatureVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 예약 Webhook 수신 endpoint다.
 *
 * <p>책임: 서명을 먼저 검증하고 실패 시 상태를 바꾸지 않는다. 검증 후에는
 * 정책 객체에 이벤트 처리를 위임한다.</p>
 * <p>핵심 불변식: 서명 실패 응답은 401이며 어떤 side effect도 발생하지 않는다.</p>
 */
@RestController
public class BookingWebhookController {

    private final ObjectMapper objectMapper;
    private final BookingEventPolicy policy;
    private final WebhookSignatureVerifier signatureVerifier;

    /**
     * Webhook 처리 협업 객체를 주입한다.
     *
     * @param objectMapper JSON 해석 mapper
     * @param policy 활성 profile의 이벤트 정책
     * @param signatureVerifier 서명 검증기
     */
    public BookingWebhookController(ObjectMapper objectMapper, BookingEventPolicy policy,
                                    WebhookSignatureVerifier signatureVerifier) {
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.signatureVerifier = signatureVerifier;
    }

    /**
     * 예약 Webhook을 수신해 서명 검증 후 처리한다.
     *
     * @param body 원본 요청 body
     * @param signatureHeader 서명 header 값
     * @return 204 처리 완료 또는 401 서명 실패
     */
    @PostMapping("/webhooks/bookings")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-OutcomeProbe-Signature", required = false)
                                        String signatureHeader) {
        if (!signatureVerifier.isValid(body, signatureHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            JsonNode payload = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            String eventId = payload.path("eventId").asText("");
            String bookingId = payload.path("data").path("bookingId").asText("");
            String stateText = payload.path("data").path("state").asText("");
            long version = payload.path("data").path("version").asLong(0);
            if (eventId.isEmpty() || bookingId.isEmpty() || stateText.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            policy.handleBookingEvent(bookingId, eventId, BookingStatus.valueOf(stateText), version);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
