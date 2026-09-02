package org.outcomeprobe.referenceconsumer.payment.adapter;

import java.nio.charset.StandardCharsets;
import org.outcomeprobe.referenceconsumer.payment.PaymentEventPolicy;
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
 * 결제 Webhook 수신 endpoint다.
 *
 * <p>책임: 서명을 먼저 검증하고, 실패하면 어떤 상태도 바꾸지 않고 401로
 * 거부한다. 검증 성공 시에만 정책 객체에 이벤트 처리를 위임한다.</p>
 * <p>핵심 불변식: 서명 검증은 원본 body 바이트로 수행한다. 재직렬화한 JSON은
 * 사용하지 않는다.</p>
 */
@RestController
public class PaymentWebhookController {

    private final ObjectMapper objectMapper;
    private final PaymentEventPolicy paymentEventPolicy;
    private final WebhookSignatureVerifier signatureVerifier;

    /**
     * Webhook 처리에 필요한 협업 객체를 주입한다.
     *
     * @param objectMapper JSON 해석 mapper
     * @param paymentEventPolicy 활성 profile의 이벤트 정책
     * @param signatureVerifier 공용 서명 검증기
     */
    public PaymentWebhookController(ObjectMapper objectMapper,
                                    PaymentEventPolicy paymentEventPolicy,
                                    WebhookSignatureVerifier signatureVerifier) {
        this.objectMapper = objectMapper;
        this.paymentEventPolicy = paymentEventPolicy;
        this.signatureVerifier = signatureVerifier;
    }

    /**
     * 결제 Webhook을 수신해 서명 검증 후 처리한다.
     *
     * <p>왜 401인가: 서명 실패는 신뢰할 수 없는 입력이므로 상태를 바꾸지 않고
     * 거부만 해야 공격 surface가 늘어나지 않는다.</p>
     *
     * @param body 원본 요청 body
     * @param signatureHeader 서명 header 값
     * @return 204 처리 완료 또는 401 서명 실패
     */
    @PostMapping("/webhooks/payments")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-OutcomeProbe-Signature", required = false)
                                        String signatureHeader) {
        if (!signatureVerifier.isValid(body, signatureHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            JsonNode payload = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            String type = payload.path("type").asText("");
            if (!"PAYMENT_SUCCEEDED".equals(type)) {
                return ResponseEntity.noContent().build();
            }
            String eventId = payload.path("eventId").asText("");
            String paymentId = payload.path("data").path("paymentId").asText("");
            if (!eventId.isEmpty() && !paymentId.isEmpty()) {
                paymentEventPolicy.handlePaymentSucceeded(paymentId, eventId);
            }
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
}
