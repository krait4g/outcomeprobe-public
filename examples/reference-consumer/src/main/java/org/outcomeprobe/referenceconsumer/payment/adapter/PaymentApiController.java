package org.outcomeprobe.referenceconsumer.payment.adapter;

import org.outcomeprobe.referenceconsumer.payment.Payment;
import org.outcomeprobe.referenceconsumer.payment.PaymentService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 승인 trigger API를 노출한다.
 *
 * <p>책임: OutcomeProbe Scenario의 target.http Step이 호출하는 업무 진입점을
 * 제공한다. 업무 판단은 PaymentService에 위임한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 202 응답은 접수와 상태 전이 시작을 의미하며
 * 최종 성공을 보장하지 않는다.</p>
 */
@RestController
public class PaymentApiController {

    private final PaymentService paymentService;

    /**
     * 결제 서비스를 주입한다.
     *
     * @param paymentService 결제 업무 서비스
     */
    public PaymentApiController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 결제 승인을 접수한다.
     *
     * @param paymentId 결제 식별자
     * @param idempotencyKey 멱등 키 header
     * @param body 승인 금액을 담은 요청 body
     * @return 처리 후 상태 요약
     */
    @PostMapping("/api/payments/{paymentId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        long amount = body != null && body.get("amount") instanceof Number number
                ? number.longValue()
                : 0L;
        Payment payment = paymentService.confirm(paymentId, amount,
                idempotencyKey == null ? "none" : idempotencyKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", payment.paymentId());
        result.put("status", payment.status().name());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /**
     * 결제 환불을 접수한다.
     *
     * @param paymentId 결제 식별자
     * @param idempotencyKey 멱등 키 header
     * @return 처리 후 상태 요약
     */
    @PostMapping("/api/payments/{paymentId}/refund")
    public ResponseEntity<Map<String, Object>> refund(
            @PathVariable String paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Payment payment = paymentService.refund(paymentId,
                idempotencyKey == null ? "refund-" + paymentId : idempotencyKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", payment.paymentId());
        result.put("status", payment.status().name());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
