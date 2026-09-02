package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.outcomeprobe.delivery.CallbackDelivery;
import io.outcomeprobe.delivery.CallbackDeliveryRepository;
import io.outcomeprobe.delivery.CallbackEvent;
import io.outcomeprobe.delivery.DeliveryRequestSnapshotHash;
import io.outcomeprobe.emulator.TemplateRenderer;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.execution.ArtifactSnapshot;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.OptimisticStateTransitionException;
import io.outcomeprobe.foundation.SeedGenerator;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ScenarioMapper;
import io.outcomeprobe.scenario.contract.ProviderContract;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Callback 계획 저장이 Run 상태 트랜잭션까지 지연되고 기존 사건 identity를 재사용하는지 검증한다.
 *
 * <p>검출 가능한 결함: Executor가 독립 트랜잭션에서 Callback 행을 먼저 확정하거나,
 * eventId 충돌 시 새로 생성한 DB id·payload hash로 Delivery를 묶어 FK 오류 또는 payload
 * 불일치를 만드는 문제.</p>
 */
class CallbackScheduleTransactionBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-08-06T01:02:03Z");
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** execute 호출만으로는 Callback·Evidence 영속화가 시작되지 않아야 한다. */
    @Test
    void persistenceIsDeferredUntilRunTransactionInvokesSupplier() {
        CallbackDeliveryRepository repository = mock(CallbackDeliveryRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        CallbackScheduleStepExecutor executor = executor(
                repository, evidenceRecorder, mock(ScenarioMapper.class), mock(ClockProvider.class));

        StepExecutionResult result = executor.execute(null);

        assertThat(result).isInstanceOf(StepExecutionResult.Transactional.class);
        verifyNoInteractions(repository, evidenceRecorder);
    }

    /**
     * 동일 runId·eventId가 이미 있으면 DB가 반환한 실제 행 id와 payload hash를
     * 새 Delivery snapshot의 권위 있는 결속 값으로 사용해야 한다.
     */
    @Test
    void existingEventRowIdentityAndPayloadHashAreReusedByDelivery() throws Exception {
        CallbackDeliveryRepository repository = mock(CallbackDeliveryRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ScenarioMapper scenarioMapper = mock(ScenarioMapper.class);
        ClockProvider clockProvider = mock(ClockProvider.class);
        when(clockProvider.now()).thenReturn(NOW);
        when(repository.lockRunForDeliveryMutation("run-1")).thenReturn(true);

        ObjectNode callbackBody = objectMapper.createObjectNode();
        callbackBody.put("eventId", "${generated.eventId}");
        callbackBody.put("observedAt", "${clock.isoInstant}");
        ProviderContract contract = new ProviderContract("provider", Map.of(), Map.of(),
                Map.of("track.updated", new ProviderContract.CallbackSpec(
                        "TRACK_UPDATED", "track", "$.version", callbackBody, null)));
        when(scenarioMapper.toContract(any())).thenReturn(contract);
        when(repository.saveEvent(any())).thenAnswer(invocation -> {
            CallbackEvent requested = invocation.getArgument(0);
            ObjectNode persistedPayload = objectMapper.createObjectNode();
            persistedPayload.put("eventId", requested.eventId());
            persistedPayload.put("observedAt", "2026-08-05T23:59:59Z");
            return new CallbackEvent("existing-event-row-id", requested.runId(),
                    requested.callbackKey(), requested.eventId(), requested.eventType(),
                    requested.resourceType(), requested.resourceId(), requested.resourceVersion(),
                    persistedPayload, "persisted-payload-hash");
        });

        CallbackScheduleStepExecutor executor = executor(
                repository, evidenceRecorder, scenarioMapper, clockProvider);
        StepExecutionResult deferred = executor.execute(context());

        StepExecutionResult materialized = ((StepExecutionResult.Transactional) deferred)
                .execution().get();

        assertThat(materialized).isInstanceOf(StepExecutionResult.Completed.class);
        ArgumentCaptor<CallbackEvent> eventCaptor = ArgumentCaptor.forClass(CallbackEvent.class);
        ArgumentCaptor<CallbackDelivery> deliveryCaptor =
                ArgumentCaptor.forClass(CallbackDelivery.class);
        InOrder persistenceOrder = inOrder(repository, evidenceRecorder);
        persistenceOrder.verify(repository).lockRunForDeliveryMutation("run-1");
        persistenceOrder.verify(repository).saveEvent(eventCaptor.capture());
        persistenceOrder.verify(evidenceRecorder).append(any());
        persistenceOrder.verify(repository).saveDelivery(deliveryCaptor.capture());
        CallbackEvent requestedEvent = eventCaptor.getValue();
        CallbackDelivery delivery = deliveryCaptor.getValue();
        assertThat(requestedEvent.payloadHash()).isNotEqualTo("persisted-payload-hash");
        assertThat(delivery.callbackEventId()).isEqualTo("existing-event-row-id");
        assertThat(delivery.requestSnapshot().path("callbackEventId").asText())
                .isEqualTo("existing-event-row-id");
        assertThat(delivery.requestSnapshot().path("payloadHash").asText())
                .isEqualTo("persisted-payload-hash");
        assertThat(DeliveryRequestSnapshotHash.isValid(
                objectMapper, delivery.requestSnapshot())).isTrue();
    }

    /** 부모 Run 잠금을 얻지 못하면 어떤 Callback·Evidence도 쓰지 않고 트랜잭션을 실패시킨다. */
    @Test
    void missingParentRunLockAbortsMaterializationBeforePersistence() throws Exception {
        CallbackDeliveryRepository repository = mock(CallbackDeliveryRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        when(repository.lockRunForDeliveryMutation("run-1")).thenReturn(false);
        CallbackScheduleStepExecutor executor = executor(
                repository, evidenceRecorder, mock(ScenarioMapper.class), mock(ClockProvider.class));

        StepExecutionResult deferred = executor.execute(context());

        assertThatThrownBy(() -> ((StepExecutionResult.Transactional) deferred).execution().get())
                .isInstanceOf(OptimisticStateTransitionException.class)
                .hasMessageContaining("run-1");
        verify(repository).lockRunForDeliveryMutation("run-1");
        verify(repository, never()).saveEvent(any());
        verify(repository, never()).saveDelivery(any());
        verifyNoInteractions(evidenceRecorder);
    }

    /** 고정 협업 객체로 Callback 실행기를 만든다. */
    private CallbackScheduleStepExecutor executor(CallbackDeliveryRepository repository,
                                                   EvidenceRecorder evidenceRecorder,
                                                   ScenarioMapper scenarioMapper,
                                                   ClockProvider clockProvider) {
        return new CallbackScheduleStepExecutor(repository, scenarioMapper,
                new TemplateRenderer(), evidenceRecorder, clockProvider,
                mock(SeedGenerator.class), objectMapper);
    }

    /** Callback snapshot과 실행 입력을 모두 가진 최소 실행 문맥을 만든다. */
    private StepExecutionContext context() throws Exception {
        ObjectNode safeSpec = objectMapper.createObjectNode();
        ArtifactSnapshot scenarioSnapshot = new ArtifactSnapshot(
                ArtifactKind.SCENARIO, "outcomeprobe.io/v1alpha1", "callback-test", "1.0.0",
                "scenario-source", "scenario-safe", "scenario-snapshot", "safe", safeSpec);
        ArtifactSnapshot contractSnapshot = new ArtifactSnapshot(
                ArtifactKind.PROVIDER_CONTRACT, "outcomeprobe.io/v1alpha1", "provider", "1.0.0",
                "contract-source", "contract-safe", "contract-snapshot", "safe", safeSpec);
        TargetProfile profile = new TargetProfile("target", "http://example.test",
                List.of("/**"), List.of("POST"), List.of("example.test"), List.of(80),
                Map.of(), Map.of(), Map.of("track.updated", "/callbacks/track"),
                List.of(), List.of());
        TargetProfileSafeSnapshot profileSnapshot = new TargetProfileSafeSnapshot(
                "outcomeprobe.io/v1alpha1", "target", "1.0.0", "profile-source",
                "profile-snapshot", profile);
        CompiledRunPlan plan = new CompiledRunPlan(
                "run-1", "callback-test", "1.0.0", "scenario-source",
                "provider", "1.0.0", "provider", "target", 7L, 30_000L, NOW,
                Map.of(), List.of(), CompiledRunPlan.CURRENT_SCHEMA_VERSION,
                scenarioSnapshot, contractSnapshot, profileSnapshot, "snapshot-set");
        ScenarioRun run = new ScenarioRun(
                "run-1", "callback-test", "1.0.0", "scenario-source", "target", 7L,
                RunStatus.RUNNING, 0, objectMapper.createObjectNode(), NOW,
                NOW.plusSeconds(30), null, null, 0, 0, false,
                null, null, NOW, null, NOW);
        RunStepExecution step = new RunStepExecution(
                "step-row", "run-1", "schedule-callback", 0,
                StepType.CALLBACK_SCHEDULE, StepStatus.PENDING,
                objectMapper.readTree("""
                        {
                          "callback": "track.updated",
                          "resource": {"type": "track", "id": "TS00000042"},
                          "events": [{
                            "eventId": "evt-fixed",
                            "state": "TRACKING",
                            "version": 3,
                            "delivery": {"duplicates": 1}
                          }]
                        }
                        """),
                null, null, null, NOW.plusSeconds(30), 0,
                null, null, 0, null, null);
        return new StepExecutionContext(run, plan, step);
    }
}
