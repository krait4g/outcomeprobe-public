package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.delivery.CallbackDeliveryRepository;
import io.outcomeprobe.emulator.AliasLockPort;
import io.outcomeprobe.emulator.ExpectedInvocation;
import io.outcomeprobe.emulator.ExpectedInvocationPort;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.infrastructure.metrics.MetricsService;
import io.outcomeprobe.profile.ProfileLookup;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.probe.ProbeAttemptIntent;
import io.outcomeprobe.probe.ProbeAttemptStart;
import io.outcomeprobe.probe.ProbeAttemptStartState;
import io.outcomeprobe.probe.ProbeAttemptTransactionCoordinator;
import io.outcomeprobe.probe.ProbeRunFence;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactRepository;
import io.outcomeprobe.scenario.ScenarioMapper;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * RunEngine의 생성·capacity·취소 경로를 단위 수준에서 검증한다.
 *
 * <p>검출 가능한 결함: capacity 초과 시 잘못된 예외 타입, terminal Run 취소 수락,
 * scenario 미존재 시 Run 저장, 취소 후 nextActionAt 미설정.</p>
 */
class RunEngineTest {

    private final ScenarioRunPort runPort = mock(ScenarioRunPort.class);
    private final RunStepPort stepPort = mock(RunStepPort.class);
    private final ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
    private final ScenarioMapper scenarioMapper = mock(ScenarioMapper.class);
    private final ScenarioCompiler scenarioCompiler = mock(ScenarioCompiler.class);
    private final ProfileLookup profileLookup = mock(ProfileLookup.class);
    private final AliasLockPort aliasLockPort = mock(AliasLockPort.class);
    private final ExpectedInvocationPort expectedInvocationPort = mock(ExpectedInvocationPort.class);
    private final EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
    private final EvidenceQueryPort evidenceQueryPort = mock(EvidenceQueryPort.class);
    private final ClockProvider clockProvider = mock(ClockProvider.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final MetricsService metricsService = mock(MetricsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RunSnapshotIntegrity snapshotIntegrity = new RunSnapshotIntegrity(objectMapper);
    private final ProbeAttemptTransactionCoordinator probeAttemptCoordinator =
            mock(ProbeAttemptTransactionCoordinator.class);
    private final CallbackDeliveryRepository callbackDeliveryRepository =
            mock(CallbackDeliveryRepository.class);

    private RunEngine engine;

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * 공통 mock 동작을 설정하고 엔진을 생성한다.
     */
    @BeforeEach
    void setUp() {
        when(clockProvider.now()).thenReturn(NOW);
        engine = new RunEngine(runPort, stepPort, artifactRepository, scenarioMapper,
                scenarioCompiler, profileLookup, snapshotIntegrity,
                new TransactionalRunStateCoordinator(runPort, stepPort),
                probeAttemptCoordinator, callbackDeliveryRepository,
                aliasLockPort, expectedInvocationPort,
                evidenceRecorder, evidenceQueryPort, clockProvider, idGenerator,
                objectMapper, List.of(), metricsService, 10);
    }

    /**
     * createRun의 capacity·검증 경로를 검증한다.
     */
    @Nested
    @DisplayName("createRun")
    class CreateRunTests {

        /**
         * capacity 초과 시 CapacityLimitExceededException을 던진다.
         */
        @Test
        @DisplayName("capacity 초과 → CapacityLimitExceededException")
        void capacityExceeded() {
            when(runPort.countActive()).thenReturn(10L);

            CreateRunRequest request = new CreateRunRequest(
                    new CreateRunRequest.ScenarioRef("s", "1.0.0"), "t", 1L, Map.of(), null);

            assertThatThrownBy(() -> engine.createRun(request))
                    .isInstanceOf(CapacityLimitExceededException.class)
                    .hasMessageContaining("상한");
            verify(runPort, never()).save(any());
        }

        /**
         * scenario 미존재 시 ArtifactImportException을 던진다.
         */
        @Test
        @DisplayName("scenario 미존재 → ArtifactImportException")
        void scenarioNotFound() {
            when(runPort.countActive()).thenReturn(0L);
            when(artifactRepository.findByIdentity(any(), eq("no-such"), eq("1.0.0")))
                    .thenReturn(Optional.empty());

            CreateRunRequest request = new CreateRunRequest(
                    new CreateRunRequest.ScenarioRef("no-such", "1.0.0"), "t", 1L, Map.of(), null);

            assertThatThrownBy(() -> engine.createRun(request))
                    .isInstanceOf(ArtifactImportException.class)
                    .hasMessageContaining("Scenario를 찾을 수 없습니다");
        }

        /**
         * target profile 미존재 시 ArtifactImportException을 던진다.
         */
        @Test
        @DisplayName("target profile 미존재 → ArtifactImportException")
        void profileNotFound() {
            when(runPort.countActive()).thenReturn(0L);
            ArtifactDefinition scenarioArtifact = mockArtifact("scenario", "payment");
            ArtifactDefinition contractArtifact = mockArtifact("contract", "mockpay");
            when(artifactRepository.findByIdentity(any(), eq("payment"), eq("1.0.0")))
                    .thenReturn(Optional.of(scenarioArtifact));
            when(scenarioMapper.toScenario(any())).thenReturn(mockScenarioDef("mockpay"));
            when(artifactRepository.findByIdentity(any(), eq("mockpay"), eq("1.0.0")))
                    .thenReturn(Optional.of(contractArtifact));
            when(scenarioMapper.toContract(any())).thenReturn(mock());
            when(profileLookup.findVersionedByName("no-profile")).thenReturn(Optional.empty());

            CreateRunRequest request = new CreateRunRequest(
                    new CreateRunRequest.ScenarioRef("payment", "1.0.0"), "no-profile", 1L, Map.of(), null);

            assertThatThrownBy(() -> engine.createRun(request))
                    .isInstanceOf(ArtifactImportException.class)
                    .hasMessageContaining("Target Profile을 찾을 수 없습니다");
        }
    }

    /** 손상된 compiled plan의 fail-closed 처리 경로를 검증한다. */
    @Nested
    @DisplayName("snapshot fail-closed")
    class SnapshotFailClosedTests {

        /** plan 역직렬화·검증 실패 시 alias와 Step을 건드리지 않고 ERROR로 끝내야 한다. */
        @Test
        void corruptedPlanEndsAsErrorWithoutAliasOrExecutorSideEffect() {
            ScenarioRun corrupted = new ScenarioRun("run-corrupt", "s", "1.0.0",
                    io.outcomeprobe.foundation.HashValue.sha256("s").hexValue(), "target", 1L,
                    RunStatus.RUNNING, 0, objectMapper.createObjectNode().put("broken", true),
                    NOW, NOW.plusSeconds(30), null, null, 0, 0, false,
                    null, null, NOW, null, NOW);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(corrupted));
            when(runPort.update(any())).thenReturn(true);

            engine.pollAndProcess();

            verify(aliasLockPort, never()).release(anyString(), anyString());
            verify(aliasLockPort, never()).tryAcquire(anyString(), anyString(), any());
            verify(callbackDeliveryRepository).cancelPendingForRun("run-corrupt", NOW);
            verify(runPort).update(argThat(updated -> updated.status() == RunStatus.ERROR
                    && ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED.name()
                    .equals(updated.problemCode())));
        }
    }

    /** DB-only Step의 계산과 상태 CAS가 같은 coordinator 경계에서 실행되는지 검증한다. */
    @Nested
    @DisplayName("transactional step")
    class TransactionalStepTests {

        /**
         * Transactional Supplier는 Executor 호출 시점이 아니라 coordinator commit 안에서 실행되고,
         * 이어지는 Step·Run CAS도 해당 경계가 열린 동안 호출돼야 한다.
         */
        @Test
        void supplierAndStepRunUpdatesShareOneCoordinatorBoundary() {
            AtomicBoolean transactionOpen = new AtomicBoolean(false);
            AtomicBoolean executorCalledOutsideTransaction = new AtomicBoolean(false);
            AtomicBoolean supplierCalledInsideTransaction = new AtomicBoolean(false);
            AtomicBoolean stepUpdatedInsideTransaction = new AtomicBoolean(false);
            AtomicBoolean runUpdatedInsideTransaction = new AtomicBoolean(false);

            StepExecutor callbackExecutor = mock(StepExecutor.class);
            when(callbackExecutor.supports()).thenReturn(StepType.CALLBACK_SCHEDULE);
            when(callbackExecutor.execute(any())).thenAnswer(invocation -> {
                executorCalledOutsideTransaction.set(!transactionOpen.get());
                return new StepExecutionResult.Transactional(() -> {
                    supplierCalledInsideTransaction.set(transactionOpen.get());
                    return new StepExecutionResult.Completed(objectMapper.createObjectNode());
                });
            });

            TransactionalRunStateCoordinator coordinator =
                    mock(TransactionalRunStateCoordinator.class);
            doAnswer(invocation -> {
                Runnable mutation = invocation.getArgument(0);
                transactionOpen.set(true);
                try {
                    mutation.run();
                } finally {
                    transactionOpen.set(false);
                }
                return null;
            }).when(coordinator).commit(any(Runnable.class));
            doAnswer(invocation -> {
                stepUpdatedInsideTransaction.set(transactionOpen.get());
                return null;
            }).when(coordinator).updateStep(any());
            doAnswer(invocation -> {
                runUpdatedInsideTransaction.set(transactionOpen.get());
                return null;
            }).when(coordinator).updateRun(any());

            RunSnapshotIntegrity integrity = mock(RunSnapshotIntegrity.class);
            RunEngine transactionalEngine = new RunEngine(
                     runPort, stepPort, artifactRepository, scenarioMapper, scenarioCompiler,
                     profileLookup, integrity, coordinator, probeAttemptCoordinator,
                     callbackDeliveryRepository, aliasLockPort,
                     expectedInvocationPort, evidenceRecorder, evidenceQueryPort,
                    clockProvider, idGenerator, objectMapper, List.of(callbackExecutor),
                    metricsService, 10);
            CompiledRunPlan plan = new CompiledRunPlan(
                    "run-atomic", "scenario", "1.0.0", "hash", "contract", "1.0.0",
                    "alias", "target", 1L, 30_000L, NOW, Map.of(),
                    List.of(new CompiledRunPlan.CompiledStep(
                            "callback", StepType.CALLBACK_SCHEDULE, 0,
                            objectMapper.createObjectNode())));
            ScenarioRun run = new ScenarioRun(
                    "run-atomic", "scenario", "1.0.0", "hash", "target", 1L,
                    RunStatus.RUNNING, 0, objectMapper.valueToTree(plan), NOW,
                    NOW.plusSeconds(30), null, null, 0, 0, false,
                    null, null, NOW, null, NOW);
            RunStepExecution step = new RunStepExecution(
                    "step-row", "run-atomic", "callback", 0,
                    StepType.CALLBACK_SCHEDULE, StepStatus.PENDING,
                    objectMapper.createObjectNode(), null, null, null,
                    NOW.plusSeconds(30), 0, null, null, 0, null, null);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId("run-atomic")).thenReturn(List.of(step));

            transactionalEngine.pollAndProcess();

            assertThat(executorCalledOutsideTransaction).isTrue();
            assertThat(supplierCalledInsideTransaction).isTrue();
            assertThat(stepUpdatedInsideTransaction).isTrue();
            assertThat(runUpdatedInsideTransaction).isTrue();
        }
    }

    /** 취소·전역 deadline이 Probe 실행기보다 먼저 처리되는 terminal 복구 경로를 검증한다. */
    @Nested
    @DisplayName("terminal Probe intent cleanup")
    class TerminalProbeIntentCleanupTests {

        /**
         * 취소 시 다음 Probe attempt의 in-flight intent를 UNKNOWN Evidence와 함께
         * 같은 Run terminal commit 안에서 중단한다.
         */
        @Test
        void cancellationInterruptsInFlightProbeInsideTerminalCommit() {
            AtomicBoolean commitOpen = new AtomicBoolean(false);
            TransactionalRunStateCoordinator coordinator =
                    mock(TransactionalRunStateCoordinator.class);
            doAnswer(invocation -> {
                Runnable mutation = invocation.getArgument(0);
                commitOpen.set(true);
                try {
                    mutation.run();
                } finally {
                    commitOpen.set(false);
                }
                return null;
            }).when(coordinator).commit(any(Runnable.class));
            doAnswer(invocation -> {
                assertThat(commitOpen).isTrue();
                return null;
            }).when(coordinator).updateStep(any());
            doAnswer(invocation -> {
                assertThat(commitOpen).isTrue();
                return null;
            }).when(coordinator).updateRun(any());
            doAnswer(invocation -> {
                assertThat(commitOpen).isTrue();
                return 1;
            }).when(callbackDeliveryRepository).cancelPendingForRun(anyString(), any());

            CompiledRunPlan plan = terminalPlan(StepType.PROBE_EVENTUALLY);
            ScenarioRun run = terminalRun(plan, true, NOW.plusSeconds(30));
            RunStepExecution step = terminalStep(StepType.PROBE_EVENTUALLY, 3);
            ProbeAttemptIntent authoritative = new ProbeAttemptIntent(
                    "attempt-4", run.id(), step.stepId(), 4, NOW.minusSeconds(1));
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of(step));
            when(probeAttemptCoordinator.interruptExisting(any(ProbeRunFence.class),
                    any(ProbeAttemptIntent.class), anyString()))
                    .thenAnswer(invocation -> {
                        assertThat(commitOpen).isTrue();
                        return Optional.of(new ProbeAttemptStart(authoritative,
                                ProbeAttemptStartState.EXISTING_IN_FLIGHT));
                    });

            terminalEngine(coordinator).pollAndProcess();

            assertThat(commitOpen).isFalse();
            ArgumentCaptor<ProbeAttemptIntent> candidate =
                    ArgumentCaptor.forClass(ProbeAttemptIntent.class);
            ArgumentCaptor<ProbeRunFence> fence =
                    ArgumentCaptor.forClass(ProbeRunFence.class);
            verify(probeAttemptCoordinator).interruptExisting(
                    fence.capture(), candidate.capture(), eq("/test-support/payments/p1"));
            assertThat(fence.getValue().boundary())
                    .isEqualTo(ProbeRunFence.Boundary.CANCEL_INTERRUPT);
            assertThat(candidate.getValue().id()).isNull();
            assertThat(candidate.getValue().runId()).isEqualTo(run.id());
            assertThat(candidate.getValue().stepId()).isEqualTo(step.stepId());
            assertThat(candidate.getValue().attemptNumber()).isEqualTo(4);
            verify(coordinator).updateRun(argThat(updated ->
                    updated.status() == RunStatus.CANCELLED));
            verify(callbackDeliveryRepository).cancelPendingForRun(run.id(), NOW);
            InOrder terminalOrder = inOrder(
                    probeAttemptCoordinator, callbackDeliveryRepository, coordinator);
            terminalOrder.verify(probeAttemptCoordinator).interruptExisting(
                    any(ProbeRunFence.class), any(ProbeAttemptIntent.class), anyString());
            terminalOrder.verify(coordinator).updateRun(argThat(updated ->
                    updated.status() == RunStatus.CANCELLED));
            terminalOrder.verify(callbackDeliveryRepository).cancelPendingForRun(run.id(), NOW);
            verify(probeAttemptCoordinator, never()).begin(any(), any(), any());
        }

        /** 전역 deadline 초과도 현재 Probe in-flight intent를 닫고 Run을 FAILED로 끝낸다. */
        @Test
        void globalDeadlineInterruptsInFlightProbeBeforeFailure() {
            CompiledRunPlan plan = terminalPlan(StepType.PROBE_EVENTUALLY);
            ScenarioRun run = terminalRun(plan, false, NOW.minusMillis(1));
            RunStepExecution step = terminalStep(StepType.PROBE_EVENTUALLY, 1);
            ProbeAttemptIntent authoritative = new ProbeAttemptIntent(
                    "attempt-2", run.id(), step.stepId(), 2, NOW.minusSeconds(1));
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of(step));
            when(stepPort.update(any())).thenReturn(true);
            when(runPort.update(any())).thenReturn(true);
            when(probeAttemptCoordinator.interruptExisting(any(ProbeRunFence.class),
                    any(ProbeAttemptIntent.class), anyString()))
                    .thenReturn(Optional.of(new ProbeAttemptStart(authoritative,
                            ProbeAttemptStartState.EXISTING_IN_FLIGHT)));

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort))
                    .pollAndProcess();

            verify(probeAttemptCoordinator).interruptExisting(
                    argThat(fence -> fence.boundary()
                            == ProbeRunFence.Boundary.DEADLINE_INTERRUPT),
                    any(ProbeAttemptIntent.class), eq("/test-support/payments/p1"));
            verify(runPort).update(argThat(updated -> updated.status() == RunStatus.FAILED
                    && ProblemCode.SCENARIO_TIMEOUT_EXCEEDED.name()
                    .equals(updated.problemCode())));
            verify(callbackDeliveryRepository).cancelPendingForRun(run.id(), NOW);
            InOrder deadlineOrder = inOrder(
                    probeAttemptCoordinator, runPort, callbackDeliveryRepository);
            deadlineOrder.verify(probeAttemptCoordinator).interruptExisting(
                    any(ProbeRunFence.class), any(ProbeAttemptIntent.class), anyString());
            deadlineOrder.verify(runPort).update(argThat(updated ->
                    updated.status() == RunStatus.FAILED));
            deadlineOrder.verify(callbackDeliveryRepository).cancelPendingForRun(run.id(), NOW);
            verify(probeAttemptCoordinator, never()).begin(any(), any(), any());
        }

        /** 전역 deadline은 현재 Step만 실패시키고 이후 Step은 실행하지 않은 상태로 건너뛴다. */
        @Test
        void globalDeadlineFailsCurrentStepAndSkipsFollowingSteps() {
            CompiledRunPlan plan = terminalPlan(StepType.EVIDENCE_AWAIT);
            ScenarioRun run = terminalRun(plan, false, NOW.minusMillis(1));
            RunStepExecution current = terminalStep(StepType.EVIDENCE_AWAIT, 2);
            RunStepExecution following = new RunStepExecution(
                    "step-row-next", run.id(), "next-step", 1,
                    StepType.RUN_WAIT, StepStatus.PENDING,
                    objectMapper.createObjectNode(), null, null, null,
                    NOW.plusSeconds(30), 0, null, null, 0, null, null);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of(current, following));
            when(stepPort.update(any())).thenReturn(true);
            when(runPort.update(any())).thenReturn(true);

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort))
                    .pollAndProcess();

            verify(stepPort).update(argThat(updated -> updated.id().equals(current.id())
                    && updated.status() == StepStatus.FAILED
                    && ProblemCode.SCENARIO_TIMEOUT_EXCEEDED.name()
                    .equals(updated.problemCode())));
            verify(stepPort).update(argThat(updated -> updated.id().equals(following.id())
                    && updated.status() == StepStatus.SKIPPED
                    && updated.problemCode() == null));
            verify(evidenceRecorder).append(argThat(evidence ->
                    evidence.type() == EvidenceType.STEP_FAILED
                            && current.stepId().equals(evidence.stepId())
                            && ProblemCode.SCENARIO_TIMEOUT_EXCEEDED.name().equals(
                            evidence.attributes().get("problemCode"))));
        }

        /** Step 자체 제한시간 실패는 전역 Run deadline과 달리 예약 Callback을 유지한다. */
        @Test
        void stepTimeoutFailureKeepsPendingCallbacks() {
            StepExecutor timeoutExecutor = mock(StepExecutor.class);
            when(timeoutExecutor.supports()).thenReturn(StepType.EVIDENCE_AWAIT);
            when(timeoutExecutor.execute(any(StepExecutionContext.class)))
                    .thenReturn(new StepExecutionResult.Failed(
                            ProblemCode.STEP_TIMEOUT_EXCEEDED,
                            "Evidence 대기 제한시간을 초과했습니다.", null));
            CompiledRunPlan plan = terminalPlan(StepType.EVIDENCE_AWAIT);
            ScenarioRun run = terminalRun(plan, false, NOW.plusSeconds(30));
            RunStepExecution step = terminalStep(StepType.EVIDENCE_AWAIT, 1);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of(step));
            when(stepPort.update(any())).thenReturn(true);
            when(runPort.update(any())).thenReturn(true);

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort),
                    List.of(timeoutExecutor)).pollAndProcess();

            verify(runPort).update(argThat(updated -> updated.status() == RunStatus.FAILED
                    && ProblemCode.STEP_TIMEOUT_EXCEEDED.name()
                    .equals(updated.problemCode())));
            verify(callbackDeliveryRepository, never()).cancelPendingForRun(anyString(), any());
            verify(probeAttemptCoordinator, never())
                    .interruptExisting(any(), any(), anyString());
        }

        /** 내부 ERROR는 대기 Callback을 같은 terminal 경계에서 취소한다. */
        @Test
        void internalErrorCancelsPendingCallbacks() {
            CompiledRunPlan plan = terminalPlan(StepType.TARGET_HTTP);
            ScenarioRun run = terminalRun(plan, false, NOW.plusSeconds(30));
            RunStepExecution step = terminalStep(StepType.TARGET_HTTP, 0);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of(step));
            when(stepPort.update(any())).thenReturn(true);
            when(runPort.update(any())).thenReturn(true);

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort))
                    .pollAndProcess();

            verify(callbackDeliveryRepository).cancelPendingForRun(run.id(), NOW);
            verify(runPort).update(argThat(updated -> updated.status() == RunStatus.ERROR));
            InOrder errorOrder = inOrder(runPort, callbackDeliveryRepository);
            errorOrder.verify(runPort).update(argThat(updated ->
                    updated.status() == RunStatus.ERROR));
            errorOrder.verify(callbackDeliveryRepository).cancelPendingForRun(run.id(), NOW);
        }

        /** 일반 판정 실패는 at-least-once Callback 전달을 취소하지 않는다. */
        @Test
        void ordinaryFailedRunKeepsPendingCallbacks() {
            CompiledRunPlan plan = terminalPlan(StepType.TARGET_HTTP);
            ScenarioRun run = terminalRun(plan, false, NOW.plusSeconds(30));
            ExpectedInvocation missing = new ExpectedInvocation(
                    "expect-1", run.id(), "expect-step", "charge", 0,
                    objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                    1, 1, 0, 0L);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of());
            when(expectedInvocationPort.unsatisfied(run.id())).thenReturn(List.of(missing));
            when(runPort.update(any())).thenReturn(true);

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort))
                    .pollAndProcess();

            verify(callbackDeliveryRepository, never()).cancelPendingForRun(anyString(), any());
            verify(runPort).update(argThat(updated -> updated.status() == RunStatus.FAILED
                    && ProblemCode.PROVIDER_EXPECTATION_UNSATISFIED.name()
                    .equals(updated.problemCode())));
        }

        /** PASSED Run의 예약 Callback도 기존 at-least-once 전달 대상으로 유지한다. */
        @Test
        void passedRunKeepsPendingCallbacks() {
            CompiledRunPlan plan = terminalPlan(StepType.TARGET_HTTP);
            ScenarioRun run = terminalRun(plan, false, NOW.plusSeconds(30));
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of());
            when(expectedInvocationPort.unsatisfied(run.id())).thenReturn(List.of());
            when(runPort.update(any())).thenReturn(true);

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort))
                    .pollAndProcess();

            verify(callbackDeliveryRepository, never()).cancelPendingForRun(anyString(), any());
            verify(runPort).update(argThat(updated -> updated.status() == RunStatus.PASSED));
        }

        /** Probe identity가 없으면 취소는 새 intent나 중단 Evidence를 만들지 않는다. */
        @Test
        void cancellationWithoutExistingIntentDoesNotCreateOrInterruptAttempt() {
            CompiledRunPlan plan = terminalPlan(StepType.PROBE_EVENTUALLY);
            ScenarioRun run = terminalRun(plan, true, NOW.plusSeconds(30));
            RunStepExecution step = terminalStep(StepType.PROBE_EVENTUALLY, 0);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of(step));
            when(stepPort.update(any())).thenReturn(true);
            when(runPort.update(any())).thenReturn(true);
            when(probeAttemptCoordinator.interruptExisting(any(ProbeRunFence.class),
                    any(ProbeAttemptIntent.class), anyString()))
                    .thenReturn(Optional.empty());

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort))
                    .pollAndProcess();

            verify(probeAttemptCoordinator).interruptExisting(
                    any(ProbeRunFence.class),
                    argThat(candidate -> candidate.attemptNumber() == 1), anyString());
            verify(probeAttemptCoordinator, never()).interrupt(any(), any(), any());
            verify(probeAttemptCoordinator, never()).begin(any(), any(), any());
            verify(runPort).update(argThat(updated ->
                    updated.status() == RunStatus.CANCELLED));
        }

        /** Probe가 아닌 현재 Step은 attempt 저장소를 조회하지 않고 기존 terminal 동작만 수행한다. */
        @Test
        void nonProbeStepDoesNotInspectOrInterruptProbeAttempts() {
            CompiledRunPlan plan = terminalPlan(StepType.TARGET_HTTP);
            ScenarioRun run = terminalRun(plan, true, NOW.plusSeconds(30));
            RunStepExecution step = terminalStep(StepType.TARGET_HTTP, 0);
            when(runPort.leaseNextDueRun(anyString(), eq(NOW), anyLong()))
                    .thenReturn(Optional.of(run));
            when(stepPort.findByRunId(run.id())).thenReturn(List.of(step));
            when(stepPort.update(any())).thenReturn(true);
            when(runPort.update(any())).thenReturn(true);

            terminalEngine(new TransactionalRunStateCoordinator(runPort, stepPort))
                    .pollAndProcess();

            verify(probeAttemptCoordinator, never()).find(any());
            verify(probeAttemptCoordinator, never())
                    .interruptExisting(any(), any(), anyString());
            verify(probeAttemptCoordinator, never()).interrupt(any(), any(), any());
            verify(probeAttemptCoordinator, never()).begin(any(), any(), any());
            verify(runPort).update(argThat(updated ->
                    updated.status() == RunStatus.CANCELLED));
        }
    }

    /**
     * requestCancel의 상태 전이를 검증한다.
     */
    @Nested
    @DisplayName("requestCancel")
    class RequestCancelTests {

        /**
         * 활성 Run은 취소 요청이 수락된다.
         */
        @Test
        @DisplayName("활성 Run 취소 → true + cancelRequested 설정")
        void activeRunAccepted() {
            ScenarioRun activeRun = new ScenarioRun("run-1", "s", "1.0.0", "h",
                    "t", 1L, RunStatus.RUNNING, 0, null,
                    NOW, null, null, null, 1, 1, false,
                    null, null, NOW, null, NOW);
            when(runPort.findById("run-1")).thenReturn(Optional.of(activeRun));
            when(runPort.update(any())).thenReturn(true);

            boolean result = engine.requestCancel("run-1");

            assertThat(result).isTrue();
        }

        /**
         * terminal Run은 취소 요청이 거부된다.
         */
        @Test
        @DisplayName("terminal Run 취소 → false")
        void terminalRunRejected() {
            ScenarioRun doneRun = new ScenarioRun("run-1", "s", "1.0.0", "h",
                    "t", 1L, RunStatus.PASSED, 0, null,
                    null, null, null, null, 1, 1, false,
                    null, null, NOW, NOW, NOW);
            when(runPort.findById("run-1")).thenReturn(Optional.of(doneRun));

            boolean result = engine.requestCancel("run-1");

            assertThat(result).isFalse();
            verify(runPort, never()).update(any());
        }

        /**
         * 미존재 Run은 false를 반환한다.
         */
        @Test
        @DisplayName("미존재 Run → false")
        void missingRunRejected() {
            when(runPort.findById("no-such")).thenReturn(Optional.empty());

            assertThat(engine.requestCancel("no-such")).isFalse();
        }
    }

    /**
     * 테스트용 ArtifactDefinition mock을 생성한다.
     */
    private ArtifactDefinition mockArtifact(String kind, String name) {
        return mock(ArtifactDefinition.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("parsedJson".equals(method)) return objectMapper.createObjectNode();
            if ("contentHash".equals(method)) return new io.outcomeprobe.foundation.HashValue("abc123");
            if ("metadata".equals(method)) return new io.outcomeprobe.scenario.ArtifactMetadata(
                    name, name, "1.0.0", Map.of());
            return null;
        });
    }

    /**
     * 테스트용 ScenarioDefinition mock을 생성한다.
     */
    private io.outcomeprobe.scenario.definition.ScenarioDefinition mockScenarioDef(String contractName) {
        return mock(io.outcomeprobe.scenario.definition.ScenarioDefinition.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("contractRef".equals(method)) return new io.outcomeprobe.scenario.definition.ScenarioDefinition.ContractRef(contractName, "1.0.0");
            if ("targetProfileRef".equals(method)) return "default-target";
            if ("steps".equals(method)) return List.of();
            if ("timeout".equals(method)) return java.time.Duration.ofSeconds(30);
            if ("variables".equals(method)) return Map.of();
            return null;
        });
    }

    /** snapshot 검증을 격리하고 지정한 terminal commit 경계를 사용하는 엔진을 만든다. */
    private RunEngine terminalEngine(TransactionalRunStateCoordinator coordinator) {
        return terminalEngine(coordinator, List.of());
    }

    /** 지정한 Step 실행기로 terminal Callback 정책을 검증하는 엔진을 만든다. */
    private RunEngine terminalEngine(TransactionalRunStateCoordinator coordinator,
                                     List<StepExecutor> stepExecutors) {
        return new RunEngine(runPort, stepPort, artifactRepository, scenarioMapper,
                scenarioCompiler, profileLookup, mock(RunSnapshotIntegrity.class), coordinator,
                probeAttemptCoordinator, callbackDeliveryRepository, aliasLockPort,
                expectedInvocationPort, evidenceRecorder,
                evidenceQueryPort, clockProvider, idGenerator, objectMapper, stepExecutors,
                metricsService, 10);
    }

    /** terminal 선점 테스트에 사용할 최소 compiled plan을 만든다. */
    private CompiledRunPlan terminalPlan(StepType stepType) {
        tools.jackson.databind.node.ObjectNode detail = objectMapper.createObjectNode();
        detail.putObject("request")
                .put("method", "GET")
                .put("path", "/test-support/payments/p1");
        return new CompiledRunPlan(
                "run-terminal", "scenario", "1.0.0", "hash", "contract", "1.0.0",
                "alias", "target", 1L, 30_000L, NOW, Map.of(),
                List.of(new CompiledRunPlan.CompiledStep(
                        "probe-step", stepType, 0, detail)));
    }

    /** 취소 또는 전역 deadline 조건을 가진 활성 Run snapshot을 만든다. */
    private ScenarioRun terminalRun(CompiledRunPlan plan, boolean cancelRequested,
                                    Instant deadlineAt) {
        return new ScenarioRun(plan.runId(), plan.scenarioName(), plan.scenarioVersion(),
                plan.scenarioHash(), plan.targetProfileName(), plan.seed(), RunStatus.RUNNING, 0,
                objectMapper.valueToTree(plan), NOW, deadlineAt,
                "worker-terminal", NOW.plusSeconds(10), 0, 0,
                cancelRequested, null, null, NOW.minusSeconds(5), null, NOW.minusSeconds(5));
    }

    /** 현재 비종결 Step과 다음 Probe attempt 번호를 재현한다. */
    private RunStepExecution terminalStep(StepType stepType, int attemptCount) {
        tools.jackson.databind.node.ObjectNode detail = objectMapper.createObjectNode();
        detail.putObject("request")
                .put("method", "GET")
                .put("path", "/test-support/payments/p1");
        StepStatus status = stepType == StepType.PROBE_EVENTUALLY
                ? StepStatus.WAITING : StepStatus.PENDING;
        return new RunStepExecution(
                "step-row", "run-terminal", "probe-step", 0, stepType, status, detail,
                null, NOW.minusSeconds(2), NOW, NOW.plusSeconds(30), attemptCount,
                null, null, 0, NOW.minusSeconds(2), null);
    }
}
