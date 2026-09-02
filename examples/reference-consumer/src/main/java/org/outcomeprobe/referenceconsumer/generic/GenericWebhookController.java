package org.outcomeprobe.referenceconsumer.generic;

import java.nio.charset.StandardCharsets;
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
 * Generic Webhook 시나리오용 이벤트 수신 endpoint다.
 *
 * <p>책임: 서명 검증, 의도적 503 주입, 정책별 이벤트 처리를 수행한다.</p>
 * <p>핵심 불변식: 서명 실패는 401로 거부하며 상태를 바꾸지 않는다.
 * 503 주입은 retry 검증을 위한 데모 전용이다.</p>
 */
@RestController
public class GenericWebhookController {

    private final ObjectMapper objectMapper;
    private final GenericEntityStore entityStore;
    private final GenericEventPolicy policy;
    private final WebhookSignatureVerifier signatureVerifier;

    /**
     * Webhook 처리 협업 객체를 주입한다.
     *
     * @param objectMapper JSON 해석 mapper
     * @param entityStore 엔티티 저장소
     * @param policy 활성 profile의 이벤트 정책
     * @param signatureVerifier 서명 검증기
     */
    public GenericWebhookController(ObjectMapper objectMapper, GenericEntityStore entityStore,
                                    GenericEventPolicy policy,
                                    WebhookSignatureVerifier signatureVerifier) {
        this.objectMapper = objectMapper;
        this.entityStore = entityStore;
        this.policy = policy;
        this.signatureVerifier = signatureVerifier;
    }

    /**
     * Generic Webhook을 수신한다.
     *
     * @param body 원본 요청 body
     * @param signatureHeader 서명 header 값
     * @return 204 처리 완료, 401 서명 실패, 503 의도적 일시 실패
     */
    @PostMapping("/webhooks/generic")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-OutcomeProbe-Signature", required = false)
                                        String signatureHeader) {
        if (!signatureVerifier.isValid(body, signatureHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            JsonNode payload = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            String entityId = payload.path("data").path("entityId").asText("");
            if (entityId.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            // retry 검증을 위해 설정된 횟수만큼 의도적으로 503을 응답한다.
            if (entityStore.consumeFailure(entityId)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            String eventId = payload.path("eventId").asText("");
            String state = payload.path("data").path("state").asText("");
            long version = payload.path("data").path("version").asLong(0);
            policy.handleGenericEvent(entityId, eventId, state, version);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
