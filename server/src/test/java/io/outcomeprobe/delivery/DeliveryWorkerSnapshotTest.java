package io.outcomeprobe.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.CanonicalJson;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.SnapshotSetHash;
import io.outcomeprobe.infrastructure.metrics.MetricsService;
import io.outcomeprobe.profile.SecretResolver;
import io.outcomeprobe.profile.TargetHeaderResolver;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Callback Delivery가 Run Profile snapshot과 실행 직전 secret만 사용하는지 검증한다. */
class DeliveryWorkerSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** 전송 직전 lease CAS를 잃은 stale Worker는 Webhook을 호출하지 않아야 한다. */
    @Test
    void lostPreSendFenceNeverSendsWebhook() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        doReturn(Optional.empty()).when(fixture.repository())
                .beginAttempt(any(), any(), anyLong());

        worker(fixture, secretRef -> Optional.empty()).poll();

        verify(fixture.sender(), never()).send(any(), anyString(), any(), any());
        verify(fixture.repository(), never()).update(any());
    }

    /** 새 전달 lease 전 terminal Run의 만료 in-flight를 먼저 결과 불명으로 닫아야 한다. */
    @Test
    void pollCleansExpiredTerminalLeaseBeforeLeasingDueDelivery() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        when(fixture.repository().leaseNextDue(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());

        worker(fixture, secretRef -> Optional.empty()).poll();

        InOrder order = inOrder(fixture.repository());
        order.verify(fixture.repository()).lockNextRunForExpiredRecovery(NOW);
        order.verify(fixture.repository())
                .cancelStrandedPendingForTerminalRun("run-1", NOW);
        order.verify(fixture.repository()).markExpiredForTerminalRunUnknown("run-1", NOW);
        order.verify(fixture.repository()).markExpiredExhaustedAttemptsUnknown("run-1", NOW);
        order.verify(fixture.repository()).leaseNextDue(anyString(), any(), anyLong());
    }

    /** recovery 소요 시간이 새 lease 기간을 줄이지 않도록 획득 직전 시각을 다시 읽어야 한다. */
    @Test
    void pollUsesFreshClockForLeaseAfterRecovery() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        Instant leaseAcquiredAt = NOW.plusSeconds(12);
        when(fixture.clock().now()).thenReturn(NOW, leaseAcquiredAt);
        when(fixture.repository().leaseNextDue(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());

        worker(fixture, secretRef -> Optional.empty()).poll();

        verify(fixture.repository()).lockNextRunForExpiredRecovery(NOW);
        verify(fixture.repository()).markExpiredForTerminalRunUnknown("run-1", NOW);
        verify(fixture.repository()).markExpiredExhaustedAttemptsUnknown("run-1", NOW);
        verify(fixture.repository()).leaseNextDue(
                anyString(), eq(leaseAcquiredAt), eq(10_000L));
    }

    /** 성공 결과는 beginAttempt에서 소비한 attempt를 다시 증가시키지 않아야 한다. */
    @Test
    void successfulSendPersistsDurableAttemptCountOnce() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        when(fixture.sender().send(any(), anyString(), any(), any()))
                .thenReturn(new WebhookSenderPort.WebhookSendResult(204, null));

        worker(fixture, secretRef -> Optional.empty()).poll();

        ArgumentCaptor<CallbackDelivery> updated = ArgumentCaptor.forClass(CallbackDelivery.class);
        verify(fixture.repository()).update(updated.capture());
        assertThat(updated.getValue().status()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(updated.getValue().attemptCount()).isEqualTo(1);
    }

    /** TLS 인증 실패는 반복 전송하지 않고 첫 시도에서 영구 실패로 확정한다. */
    @Test
    void tlsValidationFailureIsNotRetried() {
        Fixture base = fixture(profile(Map.of(), Map.of()));
        ObjectNode request = (ObjectNode) base.delivery().requestSnapshot();
        request.put("maximumAttempts", 3);
        rehash(base);
        CallbackDelivery changed = new CallbackDelivery(
                base.delivery().id(), base.delivery().runId(), base.delivery().callbackEventId(),
                base.delivery().occurrence(), base.delivery().status(), base.delivery().scheduledAt(),
                base.delivery().nextAttemptAt(), base.delivery().attemptCount(), 3,
                base.delivery().leaseOwner(), base.delivery().leaseUntil(),
                base.delivery().lastFailureCode(), base.delivery().requestSnapshot(),
                base.delivery().version(), base.delivery().completedAt());
        Fixture fixture = fixtureWithDelivery(base, changed);
        when(fixture.sender().send(any(), anyString(), any(), any()))
                .thenReturn(new WebhookSenderPort.WebhookSendResult(0, "CALLBACK_TLS_INVALID"));

        worker(fixture, secretRef -> Optional.empty()).poll();

        ArgumentCaptor<CallbackDelivery> updated = ArgumentCaptor.forClass(CallbackDelivery.class);
        verify(fixture.repository()).update(updated.capture());
        assertThat(updated.getValue().status()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(updated.getValue().lastFailureCode()).isEqualTo("CALLBACK_TLS_INVALID");
        assertThat(updated.getValue().attemptCount()).isEqualTo(1);
    }

    /** binding hash가 다르면 Webhook을 보내지 않고 occurrence를 DEAD로 끝내야 한다. */
    @Test
    void bindingMismatchNeverSendsWebhook() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        ((ObjectNode) fixture.delivery().requestSnapshot()).put(
                "runSnapshotSetHash", HashValue.sha256("tampered-set").hexValue());
        rehash(fixture);

        worker(fixture, secretRef -> Optional.empty()).poll();

        assertDeadWithoutSend(fixture, "ARTIFACT_SNAPSHOT_BINDING_MISMATCH", 1);
    }

    /** Profile secret이 누락되면 HTTP attempt 없이 TARGET_SECRET_UNRESOLVED로 DEAD여야 한다. */
    @Test
    void missingProfileSecretNeverSendsWebhook() {
        Fixture fixture = fixture(profile(Map.of(), Map.of("Authorization", "missing-auth")));

        worker(fixture, secretRef -> Optional.empty()).poll();

        assertDeadWithoutSend(fixture, "TARGET_SECRET_UNRESOLVED", 0);
    }

    /** Callback 서명 secret이 누락되면 HTTP attempt 없이 서명 구성 오류로 DEAD여야 한다. */
    @Test
    void missingSignatureSecretNeverSendsWebhook() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        ObjectNode request = (ObjectNode) fixture.delivery().requestSnapshot();
        request.put("signatureAlgorithm", "HMAC_SHA256");
        request.put("signatureHeader", "X-OutcomeProbe-Signature");
        request.put("secretRef", "callback-signing-key");
        rehash(fixture);

        worker(fixture, secretRef -> Optional.empty()).poll();

        assertDeadWithoutSend(fixture, "CALLBACK_SIGNATURE_CONFIGURATION_INVALID", 0);
    }

    /** path 한 필드라도 저장 뒤 바뀌면 request hash가 외부 호출 전에 차단해야 한다. */
    @Test
    void tamperedPathNeverSendsWebhook() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        ((ObjectNode) fixture.delivery().requestSnapshot()).put("path", "/api/tampered");

        worker(fixture, secretRef -> Optional.empty()).poll();

        assertDeadWithoutSend(fixture, "DELIVERY_REQUEST_SNAPSHOT_CORRUPTED", 1);
    }

    /** event payload 변조는 새 유효 HMAC을 만들기 전에 payload hash로 차단해야 한다. */
    @Test
    void tamperedPayloadNeverSendsWebhook() {
        Fixture fixture = fixture(profile(Map.of(), Map.of()));
        ((ObjectNode) fixture.event().payload()).put("id", "tampered-payment");

        worker(fixture, secretRef -> Optional.empty()).poll();

        assertDeadWithoutSend(fixture, "CALLBACK_EVENT_BINDING_MISMATCH", 1);
    }

    /** row의 retry 한도를 snapshot과 다르게 늘려도 추가 전송 없이 차단해야 한다. */
    @Test
    void tamperedMaximumAttemptsNeverSendsWebhook() {
        Fixture base = fixture(profile(Map.of(), Map.of()));
        CallbackDelivery changed = new CallbackDelivery(
                base.delivery().id(), base.delivery().runId(), base.delivery().callbackEventId(),
                base.delivery().occurrence(), base.delivery().status(), base.delivery().scheduledAt(),
                base.delivery().nextAttemptAt(), base.delivery().attemptCount(), 100,
                base.delivery().leaseOwner(), base.delivery().leaseUntil(),
                base.delivery().lastFailureCode(), base.delivery().requestSnapshot(),
                base.delivery().version(), base.delivery().completedAt());
        Fixture fixture = fixtureWithDelivery(base, changed);

        worker(fixture, secretRef -> Optional.empty()).poll();

        assertDeadWithoutSend(fixture, "DELIVERY_PLAN_BINDING_MISMATCH", 1);
    }

    /** null request_snapshot legacy row도 lease 반복 없이 DEAD로 확정해야 한다. */
    @Test
    void missingRequestSnapshotEndsDeadWithoutSend() {
        Fixture base = fixture(profile(Map.of(), Map.of()));
        CallbackDelivery changed = new CallbackDelivery(
                base.delivery().id(), base.delivery().runId(), base.delivery().callbackEventId(),
                base.delivery().occurrence(), base.delivery().status(), base.delivery().scheduledAt(),
                base.delivery().nextAttemptAt(), base.delivery().attemptCount(),
                base.delivery().maximumAttempts(), base.delivery().leaseOwner(),
                base.delivery().leaseUntil(), base.delivery().lastFailureCode(), null,
                base.delivery().version(), base.delivery().completedAt());
        Fixture fixture = fixtureWithDelivery(base, changed);

        worker(fixture, secretRef -> Optional.empty()).poll();

        assertDeadWithoutSend(fixture, "DELIVERY_REQUEST_SNAPSHOT_MISSING", 1);
    }

    /** Profile의 casing 변형 spoof header보다 Callback correlation header가 마지막에 우선해야 한다. */
    @Test
    void protectedCallbackHeadersOverrideProfileLayers() {
        TargetProfile profile = profile(
                Map.of("x-outcomeprobe-run-id", "default-spoof", "X-Tenant", "tenant-a"),
                Map.of("X-OutcomeProbe-Run-ID", "spoof-secret-ref"));
        Fixture fixture = fixture(profile);
        when(fixture.sender().send(any(), anyString(), any(), any()))
                .thenReturn(new WebhookSenderPort.WebhookSendResult(204, null));

        worker(fixture, secretRef -> Optional.of("secret-spoof")).poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(fixture.sender()).send(any(), anyString(), headers.capture(), any());
        assertThat(valueIgnoringCase(headers.getValue(), "X-OutcomeProbe-Run-Id"))
                .isEqualTo("run-1");
        assertThat(headers.getValue().keySet().stream()
                .filter(name -> name.equalsIgnoreCase("X-OutcomeProbe-Run-Id"))).hasSize(1);
        assertThat(headers.getValue()).containsEntry("X-Tenant", "tenant-a");
    }

    /** worker와 공용 resolver를 실제 조합으로 만든다. */
    private DeliveryWorker worker(Fixture fixture, SecretResolver secretResolver) {
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        return new DeliveryWorker(fixture.repository(), fixture.sender(),
                new SignatureService(secretResolver), new TargetHeaderResolver(secretResolver),
                new DeliveryResultTransactionCoordinator(fixture.repository(), evidenceRecorder),
                fixture.clock(), mock(MetricsService.class), fixture.mapper());
    }

    /** sender 0회와 DEAD 저장 결과를 함께 검증한다. */
    private void assertDeadWithoutSend(Fixture fixture, String failureCode, int attemptCount) {
        verify(fixture.sender(), never()).send(any(), anyString(), any(), any());
        ArgumentCaptor<CallbackDelivery> updated = ArgumentCaptor.forClass(CallbackDelivery.class);
        verify(fixture.repository()).update(updated.capture());
        assertThat(updated.getValue().status()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(updated.getValue().lastFailureCode()).isEqualTo(failureCode);
        assertThat(updated.getValue().attemptCount()).isEqualTo(attemptCount);
    }

    /** 지정 Profile을 올바른 Run snapshot binding과 delivery fixture로 만든다. */
    private Fixture fixture(TargetProfile profile) {
        ObjectMapper mapper = new ObjectMapper();
        String sourceHash = HashValue.sha256("profile").hexValue();
        String profileHash = TargetProfileSafeSnapshot.calculateCanonicalHash(mapper,
                "outcomeprobe.io/v1alpha1", "target", "1.0.0", sourceHash, profile);
        TargetProfileSafeSnapshot profileSnapshot = new TargetProfileSafeSnapshot(
                "outcomeprobe.io/v1alpha1", "target", "1.0.0", sourceHash,
                profileHash, profile);
        String scenarioHash = HashValue.sha256("scenario-snapshot").hexValue();
        String contractHash = HashValue.sha256("contract-snapshot").hexValue();
        String setHash = SnapshotSetHash.calculate(2, scenarioHash, contractHash, profileHash);
        ObjectNode request = mapper.createObjectNode();
        request.put("callbackKey", "paymentSucceeded");
        request.put("eventId", "evt-1");
        request.put("callbackEventId", "event-row-1");
        request.put("runId", "run-1");
        request.put("targetProfileName", "target");
        request.put("path", "/webhooks/payment");
        request.put("planSchemaVersion", 2);
        request.put("scenarioSnapshotHash", scenarioHash);
        request.put("contractSnapshotHash", contractHash);
        request.put("targetProfileSnapshotHash", profileHash);
        request.put("runSnapshotSetHash", setHash);
        request.set("targetProfileSnapshot", mapper.valueToTree(profileSnapshot));
        request.put("signatureAlgorithm", "NONE");
        ObjectNode payload = mapper.createObjectNode().put("id", "pay-1");
        String payloadHash = CanonicalJson.sha256(mapper, payload);
        request.put("payloadHash", payloadHash);
        request.put("plannedOccurrence", 1);
        request.put("maximumAttempts", 1);
        request.put("scheduledAt", NOW.toString());
        CallbackEvent event = new CallbackEvent("event-row-1", "run-1", "paymentSucceeded",
                "evt-1", "PAYMENT_SUCCEEDED", "payment", "pay-1", 1L,
                payload, payloadHash);
        request.put("requestSnapshotHash", DeliveryRequestSnapshotHash.calculate(mapper, request));
        CallbackDelivery delivery = new CallbackDelivery("delivery-1", "run-1", "event-row-1",
                1, DeliveryStatus.LEASED, NOW, NOW, 0, 1, "worker", NOW.plusSeconds(10),
                null, request, 0, null);
        CallbackDeliveryRepository repository = mock(CallbackDeliveryRepository.class);
        when(repository.lockRunForDeliveryMutation(anyString())).thenReturn(true);
        when(repository.lockNextRunForExpiredRecovery(any()))
                .thenReturn(Optional.of("run-1"));
        when(repository.markExpiredForTerminalRunUnknown(anyString(), any()))
                .thenReturn(List.of());
        when(repository.markExpiredExhaustedAttemptsUnknown(anyString(), any()))
                .thenReturn(List.of());
        when(repository.leaseNextDue(anyString(), any(), anyLong()))
                .thenReturn(Optional.of(delivery));
        when(repository.beginAttempt(any(), any(), anyLong())).thenAnswer(invocation -> {
            CallbackDelivery leased = invocation.getArgument(0);
            return Optional.of(new CallbackDelivery(
                    leased.id(), leased.runId(), leased.callbackEventId(), leased.occurrence(),
                    DeliveryStatus.ATTEMPTING, leased.scheduledAt(), leased.nextAttemptAt(),
                    leased.attemptCount() + 1, leased.maximumAttempts(), leased.leaseOwner(),
                    NOW.plusSeconds(10), leased.lastFailureCode(), leased.requestSnapshot(),
                    leased.version() + 1, leased.completedAt()));
        });
        when(repository.findEvent("event-row-1")).thenReturn(Optional.of(event));
        when(repository.update(any())).thenReturn(true);
        WebhookSenderPort sender = mock(WebhookSenderPort.class);
        ClockProvider clock = mock(ClockProvider.class);
        when(clock.now()).thenReturn(NOW);
        return new Fixture(mapper, repository, sender, clock, delivery, event);
    }

    /** 변경한 delivery row를 반환하도록 기존 fixture의 repository stub을 교체한다. */
    private Fixture fixtureWithDelivery(Fixture base, CallbackDelivery delivery) {
        when(base.repository().leaseNextDue(anyString(), any(), anyLong()))
                .thenReturn(Optional.of(delivery));
        return new Fixture(base.mapper(), base.repository(), base.sender(), base.clock(),
                delivery, base.event());
    }

    /** 기본·secret header를 지정한 테스트 Profile을 만든다. */
    private TargetProfile profile(Map<String, String> defaultHeaders,
                                  Map<String, String> secretHeaders) {
        return new TargetProfile("target", "http://localhost:8091",
                List.of("/**"), List.of("POST"), List.of("localhost"), List.of(8091),
                defaultHeaders, secretHeaders, Map.of(), List.of(), List.of());
    }

    /** HTTP 대소문자를 무시하고 map 값을 찾는다. */
    private String valueIgnoringCase(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** request 필드를 의도적으로 바꾼 테스트가 다음 무결성 계층까지 도달하도록 hash를 다시 계산한다. */
    private void rehash(Fixture fixture) {
        ObjectNode request = (ObjectNode) fixture.delivery().requestSnapshot();
        request.put("requestSnapshotHash",
                DeliveryRequestSnapshotHash.calculate(fixture.mapper(), request));
    }

    /**
     * Delivery Worker 테스트 협업 객체를 묶는다.
     *
     * @param mapper JSON mapper
     * @param repository Callback 저장소 mock
     * @param sender 외부 전송 포트 mock
     * @param clock 고정 시각 제공자
     * @param delivery lease된 전달 row
     * @param event 전달할 Callback event
     */
    private record Fixture(ObjectMapper mapper, CallbackDeliveryRepository repository,
                           WebhookSenderPort sender, ClockProvider clock,
                           CallbackDelivery delivery, CallbackEvent event) {
    }
}
