package io.outcomeprobe.delivery;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.CanonicalJson;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.SnapshotSetHash;
import io.outcomeprobe.profile.SecretResolutionException;
import io.outcomeprobe.profile.TargetHeaderResolutionException;
import io.outcomeprobe.profile.TargetHeaderResolver;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.scenario.contract.ProviderContract;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Callback 전달을 실행하는 durable Worker다.
 *
 * <p>책임: due occurrence를 lease해 Target Webhook으로 전송하고, 결과를
 * SUCCEEDED·RETRY_WAIT·DEAD로 기록한다. 지연·중복·역순·재시도는 occurrence
 * 행들에 이미 계획돼 있고 Worker는 그 계획을 집행한다.</p>
 * <p>핵심 불변식: Worker crash 후 lease가 만료되면 다른 Worker가 같은 occurrence를
 * 재전달할 수 있다. OutcomeProbe는 exactly-once를 보장하지 않으며, Target의
 * duplicate-safe 여부를 검증하는 것이 제품 목적이다.</p>
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final long LEASE_DURATION_MS = 10_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final CallbackDeliveryRepository deliveryRepository;
    private final WebhookSenderPort webhookSender;
    private final SignatureService signatureService;
    private final TargetHeaderResolver targetHeaderResolver;
    private final DeliveryResultTransactionCoordinator resultTransactionCoordinator;
    private final ClockProvider clockProvider;
    private final io.outcomeprobe.infrastructure.metrics.MetricsService metricsService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final String workerId;

    /**
     * 전달 Worker에 필요한 협업 객체를 주입한다.
     *
     * @param deliveryRepository 전달 저장소
     * @param webhookSender Webhook 전송 포트
     * @param signatureService 서명 서비스
     * @param targetHeaderResolver Profile·secret·보호 header 병합기
     * @param resultTransactionCoordinator 전달 결과와 Evidence의 원자 저장 경계
     * @param clockProvider 시각 제공자
     * @param metricsService 운영 메트릭 기록기
     * @param objectMapper embedded Profile snapshot 복원용 mapper
     */
    public DeliveryWorker(CallbackDeliveryRepository deliveryRepository,
                          WebhookSenderPort webhookSender, SignatureService signatureService,
                          TargetHeaderResolver targetHeaderResolver,
                          DeliveryResultTransactionCoordinator resultTransactionCoordinator,
                          ClockProvider clockProvider,
                          io.outcomeprobe.infrastructure.metrics.MetricsService metricsService,
                          tools.jackson.databind.ObjectMapper objectMapper) {
        this.deliveryRepository = deliveryRepository;
        this.webhookSender = webhookSender;
        this.signatureService = signatureService;
        this.targetHeaderResolver = targetHeaderResolver;
        this.resultTransactionCoordinator = resultTransactionCoordinator;
        this.clockProvider = clockProvider;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
        // PID만으로 workerId를 만들면 컨테이너(PID=1 고정)에서 여러 replica가 충돌한다.
        this.workerId = "delivery-worker-" + ProcessHandle.current().pid()
                + "-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName().hashCode();
    }

    /**
     * due occurrence를 polling해 하나를 처리한다.
     *
     * <p>한 번의 tick에서 하나만 처리해 lease 점유 시간을 짧게 유지한다.</p>
     */
    @Scheduled(fixedDelay = 150L)
    public void poll() {
        try {
            Instant recoveryObservedAt = clockProvider.now();
            // terminal 또는 attempt 상한 소진 lease를 재전송 전에 결과 불명으로 먼저 닫는다.
            resultTransactionCoordinator.recoverExpiredUncertainDeliveries(recoveryObservedAt);
            // 복구 시간이 새 lease budget을 잠식하지 않도록 획득 직전 시각을 다시 읽는다.
            Instant leaseAcquiredAt = clockProvider.now();
            deliveryRepository.leaseNextDue(workerId, leaseAcquiredAt, LEASE_DURATION_MS)
                    .ifPresent(this::deliverOne);
        } catch (Exception e) {
            log.error("callback 전달 처리 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * lease된 delivery 하나를 전송하고 결과를 SUCCEEDED·RETRY_WAIT·DEAD로 확정한다.
     * Target 2xx 후 completion 저장 전 장애는 lease 만료로 재전달되는 at-least-once 경로다.
     */
    private void deliverOne(CallbackDelivery delivery) {
        JsonNode request = delivery.requestSnapshot();
        String runId = delivery.runId();
        if (request == null || !request.isObject()) {
            markDead(delivery, "", "", "DELIVERY_REQUEST_SNAPSHOT_MISSING");
            return;
        }
        String callbackKey = request.path("callbackKey").asText("");
        String eventId = request.path("eventId").asText("");

        if (!DeliveryRequestSnapshotHash.isValid(objectMapper, request)) {
            markDead(delivery, callbackKey, eventId, "DELIVERY_REQUEST_SNAPSHOT_CORRUPTED");
            return;
        }
        if (!matchesDeliveryPlan(delivery, request)) {
            markDead(delivery, callbackKey, eventId, "DELIVERY_PLAN_BINDING_MISMATCH");
            return;
        }

        Optional<CallbackEvent> eventOpt = deliveryRepository.findEvent(delivery.callbackEventId());
        if (eventOpt.isEmpty()) {
            markDead(delivery, callbackKey, eventId, "EVENT_MISSING");
            return;
        }
        CallbackEvent event = eventOpt.get();
        String actualPayloadHash = event.payload() == null
                ? "" : CanonicalJson.sha256(objectMapper, event.payload());
        if (!runId.equals(request.path("runId").asText(""))
                || !runId.equals(event.runId())
                || !callbackKey.equals(event.callbackKey())
                || !eventId.equals(event.eventId())
                || !actualPayloadHash.equals(event.payloadHash())
                || !actualPayloadHash.equals(request.path("payloadHash").asText(""))) {
            markDead(delivery, callbackKey, eventId, "CALLBACK_EVENT_BINDING_MISMATCH");
            return;
        }

        JsonNode profileNode = request.get("targetProfileSnapshot");
        if (profileNode == null || !profileNode.isObject()) {
            markDead(delivery, callbackKey, eventId, "ARTIFACT_SNAPSHOT_MISSING");
            return;
        }
        TargetProfileSafeSnapshot profileSnapshot;
        try {
            profileSnapshot = objectMapper.treeToValue(profileNode, TargetProfileSafeSnapshot.class);
        } catch (tools.jackson.core.JacksonException exception) {
            markDead(delivery, callbackKey, eventId, "ARTIFACT_SNAPSHOT_CORRUPTED");
            return;
        }
        if (!profileSnapshot.hasValidCanonicalHash(objectMapper)) {
            markDead(delivery, callbackKey, eventId, "ARTIFACT_SNAPSHOT_CORRUPTED");
            return;
        }
        int planSchemaVersion = request.path("planSchemaVersion").asInt(0);
        String profileHash = request.path("targetProfileSnapshotHash").asText("");
        String expectedSetHash = request.path("runSnapshotSetHash").asText("");
        String actualSetHash = SnapshotSetHash.calculate(
                planSchemaVersion,
                request.path("scenarioSnapshotHash").asText(""),
                request.path("contractSnapshotHash").asText(""),
                profileSnapshot.canonicalSnapshotHash());
        if (planSchemaVersion != 2
                || !profileHash.equals(profileSnapshot.canonicalSnapshotHash())
                || !expectedSetHash.equals(actualSetHash)) {
            markDead(delivery, callbackKey, eventId, "ARTIFACT_SNAPSHOT_BINDING_MISMATCH");
            return;
        }
        TargetProfile profile = profileSnapshot.profile();
        String path = request.path("path").asText("");

        byte[] body = event.payload().toString().getBytes(StandardCharsets.UTF_8);
        Map<String, String> explicitHeaders = Map.of("Content-Type", "application/json");
        Map<String, String> protectedHeaders = new LinkedHashMap<>();
        protectedHeaders.put("X-OutcomeProbe-Run-Id", runId);
        protectedHeaders.put("X-OutcomeProbe-Event-Id", event.eventId());
        protectedHeaders.put("X-OutcomeProbe-Delivery-Id", delivery.id());
        protectedHeaders.put("X-OutcomeProbe-Delivery-Occurrence", String.valueOf(delivery.occurrence()));
        try {
            applySignature(request, body, protectedHeaders);
        } catch (SecretResolutionException | IllegalStateException exception) {
            markConfigurationDead(delivery, callbackKey, eventId,
                    "CALLBACK_SIGNATURE_CONFIGURATION_INVALID");
            return;
        }

        Map<String, String> headers;
        try {
            headers = targetHeaderResolver.resolve(profile, explicitHeaders, protectedHeaders);
        } catch (SecretResolutionException exception) {
            markConfigurationDead(delivery, callbackKey, eventId, "TARGET_SECRET_UNRESOLVED");
            return;
        } catch (TargetHeaderResolutionException exception) {
            markConfigurationDead(delivery, callbackKey, eventId,
                    "CALLBACK_SIGNATURE_CONFIGURATION_INVALID");
            return;
        }

        Instant attemptStartedAt = clockProvider.now();
        AppendEvidenceCommand attemptedEvidence = AppendEvidenceCommand
                .of(runId, EvidenceType.CALLBACK_DELIVERY_ATTEMPTED, EvidenceSource.DELIVERY)
                .withCorrelation(null, event.resourceType(), event.resourceId())
                .withAttribute("callback", callbackKey)
                .withAttribute("eventId", eventId)
                .withAttribute("occurrence", String.valueOf(delivery.occurrence()))
                .withAttribute("attempt", String.valueOf(delivery.attemptCount() + 1))
                .withObservedAt(attemptStartedAt);
        Optional<CallbackDelivery> attempting = resultTransactionCoordinator.beginAttempt(
                delivery, attemptStartedAt, LEASE_DURATION_MS, attemptedEvidence);
        if (attempting.isEmpty()) {
            // terminal UNKNOWN 또는 다른 Worker가 권한을 가져간 stale snapshot은 전송하지 않는다.
            return;
        }
        delivery = attempting.get();

        WebhookSenderPort.WebhookSendResult result = webhookSender.send(profile, path, headers, body);
        Instant now = clockProvider.now();

        if (result.reached() && isSuccess(request, result.statusCode())) {
            CallbackDelivery done = withResult(delivery, DeliveryStatus.SUCCEEDED,
                    delivery.attemptCount(), null, now, now);
            resultTransactionCoordinator.commit(done, List.of(AppendEvidenceCommand
                    .of(runId, EvidenceType.CALLBACK_DELIVERY_SUCCEEDED, EvidenceSource.DELIVERY)
                    .withCorrelation(null, event.resourceType(), event.resourceId())
                    .withAttribute("callback", callbackKey)
                    .withAttribute("eventId", eventId)
                    .withAttribute("occurrence", String.valueOf(delivery.occurrence()))
                    .withAttribute("status", String.valueOf(result.statusCode()))));
            metricsService.callbackDelivery(callbackKey, "SUCCEEDED");
            return;
        }

        String failureCode = result.reached() ? "HTTP_" + result.statusCode() : result.failureCode();
        boolean retryable = result.reached()
                ? isRetryableStatus(request, result.statusCode())
                : isRetryableTransportFailure(failureCode);
        if (retryable) {
            scheduleRetryOrFail(delivery, request, event, callbackKey, eventId, failureCode, now);
        } else {
            AppendEvidenceCommand failedEvidence = AppendEvidenceCommand
                    .of(runId, EvidenceType.CALLBACK_DELIVERY_FAILED, EvidenceSource.DELIVERY)
                    .withCorrelation(null, event.resourceType(), event.resourceId())
                    .withAttribute("callback", callbackKey)
                    .withAttribute("eventId", eventId)
                    .withAttribute("occurrence", String.valueOf(delivery.occurrence()))
                    .withAttribute("failureCode", String.valueOf(failureCode));
            markAttemptDead(delivery, callbackKey, eventId, failureCode, List.of(failedEvidence));
            metricsService.callbackDelivery(callbackKey, "FAILED");
        }
    }

    /**
     * 재시도 가능 실패를 backoff와 함께 RETRY_WAIT으로 전환하거나 한계 초과 시 DEAD로 확정한다.
     */
    private void scheduleRetryOrFail(CallbackDelivery delivery, JsonNode request, CallbackEvent event,
                                     String callbackKey, String eventId, String failureCode, Instant now) {
        int attempts = delivery.attemptCount();
        if (attempts >= delivery.maximumAttempts()) {
            markAttemptDead(delivery, callbackKey, eventId, failureCode, List.of());
            return;
        }
        long backoffMs = backoffMillis(request, attempts);
        CallbackDelivery waiting = withResult(delivery, DeliveryStatus.RETRY_WAIT,
                attempts, failureCode, now.plusMillis(backoffMs), null);
        resultTransactionCoordinator.commit(waiting, List.of(AppendEvidenceCommand
                .of(delivery.runId(), EvidenceType.CALLBACK_DELIVERY_RETRY_SCHEDULED, EvidenceSource.DELIVERY)
                .withCorrelation(null, event.resourceType(), event.resourceId())
                .withAttribute("callback", callbackKey)
                .withAttribute("eventId", eventId)
                .withAttribute("occurrence", String.valueOf(delivery.occurrence()))
                .withAttribute("attempt", String.valueOf(attempts))
                .withAttribute("nextBackoffMs", String.valueOf(backoffMs))
                .withAttribute("faultOrigin", "TARGET_OBSERVED")));
    }

    /**
     * delivery를 DEAD로 확정하고 증거를 남긴다. DEAD는 자동 재실행되지 않는다.
     */
    private void markDead(CallbackDelivery delivery, String callbackKey, String eventId, String failureCode) {
        markDead(delivery, callbackKey, eventId, failureCode, List.of());
    }

    /**
     * delivery를 DEAD로 확정하면서 같은 실패에 속한 선행 Evidence도 원자적으로 기록한다.
     *
     * @param delivery 확정할 occurrence
     * @param callbackKey Callback 이름
     * @param eventId 외부 Event 식별자
     * @param failureCode secret을 포함하지 않는 실패 코드
     * @param precedingEvidence DEAD와 같은 트랜잭션에 포함할 선행 Evidence
     */
    private void markDead(CallbackDelivery delivery, String callbackKey, String eventId,
                          String failureCode, List<AppendEvidenceCommand> precedingEvidence) {
        CallbackDelivery dead = withResult(delivery, DeliveryStatus.DEAD,
                delivery.attemptCount() + 1, failureCode, clockProvider.now(), clockProvider.now());
        commitDead(delivery, callbackKey, eventId, failureCode, precedingEvidence, dead);
    }

    /**
     * 이미 durable하게 증가한 HTTP attempt를 이중 증가시키지 않고 DEAD로 확정한다.
     *
     * @param delivery ATTEMPTING occurrence
     * @param callbackKey Callback 이름
     * @param eventId 외부 Event 식별자
     * @param failureCode secret을 포함하지 않는 실패 코드
     * @param precedingEvidence DEAD와 같은 트랜잭션에 포함할 선행 Evidence
     */
    private void markAttemptDead(CallbackDelivery delivery, String callbackKey, String eventId,
                                 String failureCode,
                                 List<AppendEvidenceCommand> precedingEvidence) {
        CallbackDelivery dead = withResult(delivery, DeliveryStatus.DEAD,
                delivery.attemptCount(), failureCode, clockProvider.now(), clockProvider.now());
        commitDead(delivery, callbackKey, eventId, failureCode, precedingEvidence, dead);
    }

    /**
     * 계산된 DEAD snapshot과 관련 Evidence를 한 transaction으로 저장한다.
     *
     * @param delivery 원본 occurrence
     * @param callbackKey Callback 이름
     * @param eventId 외부 Event 식별자
     * @param failureCode secret을 포함하지 않는 실패 코드
     * @param precedingEvidence DEAD보다 먼저 기록할 Evidence
     * @param dead 최종 DEAD snapshot
     */
    private void commitDead(CallbackDelivery delivery, String callbackKey, String eventId,
                            String failureCode, List<AppendEvidenceCommand> precedingEvidence,
                            CallbackDelivery dead) {
        java.util.ArrayList<AppendEvidenceCommand> evidenceCommands =
                new java.util.ArrayList<>(precedingEvidence);
        evidenceCommands.add(AppendEvidenceCommand
                .of(delivery.runId(), EvidenceType.CALLBACK_DELIVERY_DEAD, EvidenceSource.DELIVERY)
                .withAttribute("callback", callbackKey)
                .withAttribute("eventId", eventId)
                .withAttribute("occurrence", String.valueOf(delivery.occurrence()))
                .withAttribute("failureCode", String.valueOf(failureCode)));
        resultTransactionCoordinator.commit(dead, evidenceCommands);
        metricsService.callbackDelivery(callbackKey, "DEAD");
    }

    /** immutable delivery row 의미와 request snapshot의 계획 binding이 일치하는지 확인한다. */
    private boolean matchesDeliveryPlan(CallbackDelivery delivery, JsonNode request) {
        if (!delivery.callbackEventId().equals(request.path("callbackEventId").asText(""))
                || delivery.occurrence() != request.path("plannedOccurrence").asInt(-1)
                || delivery.maximumAttempts() != request.path("maximumAttempts").asInt(-1)) {
            return false;
        }
        try {
            return delivery.scheduledAt().equals(
                    Instant.parse(request.path("scheduledAt").asText("")));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * HTTP 전송 전 구성 오류를 attempt 증가 없이 DEAD로 확정한다.
     *
     * @param delivery 대상 occurrence
     * @param callbackKey Callback 이름
     * @param eventId 외부 Event 식별자
     * @param failureCode secret 값이 없는 고정 오류 코드
     */
    private void markConfigurationDead(CallbackDelivery delivery, String callbackKey,
                                       String eventId, String failureCode) {
        CallbackDelivery dead = withResult(delivery, DeliveryStatus.DEAD,
                delivery.attemptCount(), failureCode, clockProvider.now(), clockProvider.now());
        resultTransactionCoordinator.commit(dead, List.of(AppendEvidenceCommand
                .of(delivery.runId(), EvidenceType.CALLBACK_DELIVERY_DEAD, EvidenceSource.DELIVERY)
                .withAttribute("callback", callbackKey)
                .withAttribute("eventId", eventId)
                .withAttribute("occurrence", String.valueOf(delivery.occurrence()))
                .withAttribute("failureCode", failureCode)));
        metricsService.callbackDelivery(callbackKey, "DEAD");
    }

    /**
     * Contract 서명 계약으로 body 서명 header를 계산·부착한다.
     * INVALID_SIGNATURE fault는 의도적으로 서명을 위조한다.
     */
    private void applySignature(JsonNode request, byte[] body, Map<String, String> headers) {
        String algorithm = request.path("signatureAlgorithm").asText("NONE");
        if (algorithm == null || "NONE".equalsIgnoreCase(algorithm)) {
            return;
        }
        String headerName = request.path("signatureHeader").asText("X-OutcomeProbe-Signature");
        if (isCorrelationHeader(headerName)) {
            throw new IllegalStateException("서명 header가 Callback correlation header와 충돌합니다.");
        }
        ProviderContract.SignatureSpec spec = new ProviderContract.SignatureSpec(
                algorithm, headerName, request.path("secretRef").asText(null), "HEX_LOWER", "RAW_BODY");
        String signature = signatureService.sign(spec, body)
                .orElseThrow(() -> new IllegalStateException("Callback 서명 값이 생성되지 않았습니다."));
        if ("INVALID_SIGNATURE".equalsIgnoreCase(request.path("signatureFault").asText(""))) {
            // Scenario가 의도한 서명 위조를 재현한다. Target은 이 이벤트를 거부해야 한다.
            signature = signature.isEmpty() ? "deadbeef" : flipFirstHex(signature);
        }
        headers.put(headerName, signature);
    }

    /** Profile이나 Contract가 OutcomeProbe correlation header를 덮어쓰지 못하게 판정한다. */
    private boolean isCorrelationHeader(String headerName) {
        return headerName != null && java.util.Set.of(
                        "X-OutcomeProbe-Run-Id",
                        "X-OutcomeProbe-Event-Id",
                        "X-OutcomeProbe-Delivery-Id",
                        "X-OutcomeProbe-Delivery-Occurrence")
                .stream().anyMatch(protectedName -> protectedName.equalsIgnoreCase(headerName));
    }

    /**
     * 서명 첫 글자를 뒤집어 위조 서명을 만든다. Target은 이를 거부해야 한다.
     */
    private String flipFirstHex(String signature) {
        char first = signature.charAt(0);
        char flipped = first == '0' ? '1' : '0';
        return flipped + signature.substring(1);
    }

    /**
     * 응답이 성공 조건(2xx 또는 successStatuses)에 해당하는지 판정한다.
     */
    private boolean isSuccess(JsonNode request, int statusCode) {
        JsonNode successStatuses = request.path("successStatuses");
        if (successStatuses.isArray() && !successStatuses.isEmpty()) {
            for (JsonNode candidate : successStatuses) {
                if (candidate.asInt() == statusCode) {
                    return true;
                }
            }
            return false;
        }
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 응답 상태가 retryOnStatus에 포함되는지 판정한다.
     */
    private boolean isRetryableStatus(JsonNode request, int statusCode) {
        JsonNode retryOn = request.path("retryOnStatus");
        if (retryOn.isArray()) {
            for (JsonNode candidate : retryOn) {
                if (candidate.asInt() == statusCode) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 일시적인 연결 실패만 재시도하고 TLS 인증 실패와 중단은 영구 오류로 확정한다.
     */
    private boolean isRetryableTransportFailure(String failureCode) {
        return "CONNECTION_FAILED".equals(failureCode);
    }

    /**
     * 시도 횟수에 따른 backoff를 계산하고 상한을 적용한다.
     */
    private long backoffMillis(JsonNode request, int attempts) {
        long initial = request.path("initialBackoffMs").asLong(200L);
        double multiplier = request.path("multiplier").asDouble(2.0);
        double value = initial * Math.pow(multiplier, Math.max(0, attempts - 1));
        return (long) Math.min(value, MAX_BACKOFF_MS);
    }

    /**
     * delivery 상태 전이 결과를 새 snapshot으로 만든다.
     */
    private CallbackDelivery withResult(CallbackDelivery delivery, DeliveryStatus status, int attempts,
                                        String failureCode, Instant nextAttemptAt, Instant completedAt) {
        return new CallbackDelivery(
                delivery.id(), delivery.runId(), delivery.callbackEventId(), delivery.occurrence(),
                status, delivery.scheduledAt(), nextAttemptAt, attempts, delivery.maximumAttempts(),
                delivery.leaseOwner(), delivery.leaseUntil(), failureCode, delivery.requestSnapshot(),
                delivery.version(), completedAt);
    }
}
