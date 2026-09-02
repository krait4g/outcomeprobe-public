package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.execution.step.ProbeEventuallyStepExecutor;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.infrastructure.metrics.MetricsService;
import io.outcomeprobe.profile.TargetHeaderResolver;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Probe intent 선기록 뒤 장애가 발생해도 HTTP를 중복 전송하지 않는 복구 경계를 검증한다.
 *
 * <p>검증 원칙: begin 실패와 기존 intent는 HTTP보다 앞에서 처리하며, 결과 저장 실패 뒤
 * 같은 attempt 재진입은 기존 intent를 중단 처리한 다음에만 다음 번호로 진행한다.</p>
 */
class ProbeEventuallyRecoveryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProbeAttemptTransactionCoordinator coordinator =
            mock(ProbeAttemptTransactionCoordinator.class);

    /** begin 트랜잭션 실패 시 HTTP를 한 번도 호출하지 않는다. */
    @Test
    void beginFailureNeverCallsHttp() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        when(coordinator.begin(any(ProbeRunFence.class), any(ProbeAttemptIntent.class),
                any(AppendEvidenceCommand.class)))
                .thenThrow(new IllegalStateException("intent 저장 실패"));

        assertThatThrownBy(() -> executor(probe, clockAt(Instant.EPOCH))
                .execute(context(Instant.EPOCH, 0, "30s", "100ms")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("intent 저장 실패");

        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /**
     * 결과 미확정 intent는 HTTP 없이 중단하고 다음 실행 후보를 다음 attempt 번호로 만든다.
     */
    @Test
    void existingInFlightInterruptsWithoutHttpAndAdvancesAttempt() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeAttemptIntent firstIntent = intent("attempt-1", 1, Instant.EPOCH);
        ProbeAttemptIntent secondIntent = intent("attempt-2", 2, Instant.EPOCH.plusMillis(100));
        when(coordinator.begin(any(ProbeRunFence.class), any(ProbeAttemptIntent.class),
                any(AppendEvidenceCommand.class)))
                .thenReturn(new ProbeAttemptStart(firstIntent,
                                ProbeAttemptStartState.EXISTING_IN_FLIGHT),
                        new ProbeAttemptStart(secondIntent,
                                ProbeAttemptStartState.EXISTING_FINALIZED));

        StepExecutionResult first = executor(probe, clockAt(Instant.EPOCH))
                .execute(context(Instant.EPOCH, 0, "30s", "100ms"));
        assertThat(first).isInstanceOf(StepExecutionResult.Transactional.class);
        StepExecutionResult recovered = resolve(first);

        assertThat(recovered).isInstanceOfSatisfying(StepExecutionResult.Waiting.class,
                waiting -> {
                    assertThat(waiting.observation().path("attempts").asInt()).isEqualTo(1);
                    assertThat(waiting.observation().path("consecutive").asInt()).isZero();
                });

        StepExecutionResult next = executor(probe, clockAt(Instant.EPOCH.plusMillis(100)))
                .execute(context(Instant.EPOCH, 1, "30s", "100ms"));
        assertThat(next).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.INTERNAL_EXECUTION_ERROR));

        ArgumentCaptor<ProbeAttemptIntent> candidates =
                ArgumentCaptor.forClass(ProbeAttemptIntent.class);
        verify(coordinator, times(2)).begin(any(ProbeRunFence.class), candidates.capture(),
                any(AppendEvidenceCommand.class));
        assertThat(candidates.getAllValues())
                .extracting(ProbeAttemptIntent::attemptNumber)
                .containsExactly(1, 2);
        ArgumentCaptor<AppendEvidenceCommand> interruption =
                ArgumentCaptor.forClass(AppendEvidenceCommand.class);
        verify(coordinator).interrupt(any(ProbeRunFence.class), eq(firstIntent),
                interruption.capture());
        assertThat(interruption.getValue().type())
                .isEqualTo(EvidenceType.PROBE_ATTEMPT_INTERRUPTED);
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 기존 in-flight 복구는 현재 secret이 사라졌어도 재전송 없이 진행해야 한다. */
    @Test
    void existingInFlightRecoveryDoesNotRequireSecretResolution() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeAttemptIntent authoritative = intent("attempt-1", 1, Instant.EPOCH);
        when(coordinator.find(any(ProbeAttemptIntent.class)))
                .thenReturn(Optional.of(new ProbeAttemptStart(authoritative,
                        ProbeAttemptStartState.EXISTING_IN_FLIGHT)));
        StepExecutionContext context = context(Instant.EPOCH, 0, "30s", "100ms",
                List.of("GET"), Map.of("Authorization", "missing-secret"));

        StepExecutionResult result = executor(probe, clockAt(Instant.EPOCH)).execute(context);
        StepExecutionResult recovered = resolve(result);

        assertThat(recovered).isInstanceOf(StepExecutionResult.Waiting.class);
        verify(coordinator).find(any(ProbeAttemptIntent.class));
        verify(coordinator).interrupt(any(ProbeRunFence.class), eq(authoritative),
                any(AppendEvidenceCommand.class));
        verify(coordinator, never()).begin(any(), any(), any());
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 완료 저장 실패 뒤 같은 attempt를 재호출해도 HTTP는 다시 전송하지 않는다. */
    @Test
    void completeFailureDoesNotResendSameAttempt() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeAttemptIntent authoritative = intent("attempt-1", 1, Instant.EPOCH);
        when(coordinator.begin(any(ProbeRunFence.class), any(ProbeAttemptIntent.class),
                any(AppendEvidenceCommand.class)))
                .thenReturn(new ProbeAttemptStart(authoritative,
                                ProbeAttemptStartState.CREATED),
                        new ProbeAttemptStart(authoritative,
                                ProbeAttemptStartState.EXISTING_IN_FLIGHT));
        when(probe.probe(any(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(observation("PROCESSING", Instant.EPOCH, Instant.EPOCH));
        doThrow(new IllegalStateException("결과 저장 실패"))
                .when(coordinator).complete(any(ProbeRunFence.class),
                        any(ProbeAttempt.class), any());

        StepExecutionContext sameAttempt = context(Instant.EPOCH, 0, "30s", "100ms");
        StepExecutionResult first = executor(probe, clockAt(Instant.EPOCH))
                .execute(sameAttempt);
        StepExecutionResult.Deferred deferred =
                (StepExecutionResult.Deferred) first;
        assertThatThrownBy(deferred.persistence()::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("결과 저장 실패");

        StepExecutionResult second = executor(probe, clockAt(Instant.EPOCH))
                .execute(sameAttempt);
        assertThat(resolve(second)).isInstanceOf(StepExecutionResult.Waiting.class);

        ArgumentCaptor<ProbeAttemptIntent> candidates =
                ArgumentCaptor.forClass(ProbeAttemptIntent.class);
        verify(coordinator, times(2)).begin(any(ProbeRunFence.class), candidates.capture(),
                any(AppendEvidenceCommand.class));
        assertThat(candidates.getAllValues())
                .extracting(ProbeAttemptIntent::attemptNumber)
                .containsExactly(1, 1);
        verify(coordinator).interrupt(any(ProbeRunFence.class), eq(authoritative),
                any(AppendEvidenceCommand.class));
        verify(probe, times(1)).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 재시작 시각이 늦어도 intent 시각부터 계산한 최초 within과 시작 시각을 보존한다. */
    @Test
    void authoritativeIntentPreservesRecoveredWithinDeadline() throws Exception {
        Instant restartedAt = Instant.EPOCH.plusSeconds(2);
        StateProbePort probe = mock(StateProbePort.class);
        ProbeAttemptIntent authoritative = intent("attempt-1", 1, Instant.EPOCH);
        when(coordinator.begin(any(ProbeRunFence.class), any(ProbeAttemptIntent.class),
                any(AppendEvidenceCommand.class)))
                .thenReturn(new ProbeAttemptStart(authoritative,
                        ProbeAttemptStartState.EXISTING_IN_FLIGHT));

        StepExecutionResult result = executor(probe, clockAt(restartedAt))
                .execute(context(restartedAt, 0, "5s", "10s"));
        StepExecutionResult recovered = resolve(result);

        assertThat(recovered).isInstanceOfSatisfying(StepExecutionResult.Waiting.class,
                waiting -> {
                    assertThat(waiting.nextActionAt()).isEqualTo(Instant.EPOCH.plusSeconds(5));
                    assertThat(waiting.observation().path("probeStartedAt").asText())
                            .isEqualTo(Instant.EPOCH.toString());
                });
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 이미 확정된 intent를 만나면 내부 상태 불일치로 닫고 HTTP를 호출하지 않는다. */
    @Test
    void existingFinalizedFailsClosedWithoutHttp() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeAttemptIntent authoritative = intent("attempt-1", 1, Instant.EPOCH);
        when(coordinator.begin(any(ProbeRunFence.class), any(ProbeAttemptIntent.class),
                any(AppendEvidenceCommand.class)))
                .thenReturn(new ProbeAttemptStart(authoritative,
                        ProbeAttemptStartState.EXISTING_FINALIZED));

        StepExecutionResult result = executor(probe, clockAt(Instant.EPOCH))
                .execute(context(Instant.EPOCH, 0, "30s", "100ms"));

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.INTERNAL_EXECUTION_ERROR));
        verify(coordinator, never()).interrupt(any(), any(), any());
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** deadline 뒤 기존 in-flight는 begin·HTTP 없이 찾아 중단한 다음 실패해야 한다. */
    @Test
    void expiredDeadlineInterruptsExistingIntentWithoutBeginOrHttp() throws Exception {
        Instant expiredAt = Instant.EPOCH.plusSeconds(6);
        StateProbePort probe = mock(StateProbePort.class);
        ProbeAttemptIntent authoritative = intent(
                "attempt-2", 2, Instant.EPOCH.plusSeconds(1));
        when(coordinator.interruptExisting(any(ProbeRunFence.class),
                any(ProbeAttemptIntent.class), anyString()))
                .thenReturn(Optional.of(new ProbeAttemptStart(authoritative,
                        ProbeAttemptStartState.EXISTING_IN_FLIGHT)));

        StepExecutionResult result = executor(probe, clockAt(expiredAt))
                .execute(context(Instant.EPOCH, 1, "5s", "100ms"));
        assertThat(result).isInstanceOf(StepExecutionResult.Transactional.class);
        StepExecutionResult recovered = resolve(result);

        assertThat(recovered).isInstanceOfSatisfying(StepExecutionResult.Failed.class,
                failed -> {
                    assertThat(failed.problemCode())
                            .isEqualTo(ProblemCode.PROBE_DEADLINE_EXCEEDED);
                    assertThat(failed.detailJson().path("attempts").asInt()).isEqualTo(2);
                    assertThat(failed.detailJson().path("interruptedAttempt").asBoolean())
                            .isTrue();
                });
        ArgumentCaptor<ProbeAttemptIntent> candidate =
                ArgumentCaptor.forClass(ProbeAttemptIntent.class);
        verify(coordinator).interruptExisting(any(ProbeRunFence.class), candidate.capture(),
                anyString());
        assertThat(candidate.getValue().id()).isNull();
        assertThat(candidate.getValue().attemptNumber()).isEqualTo(2);
        assertThat(candidate.getValue().requestedAt()).isEqualTo(expiredAt);
        verify(coordinator, never()).begin(any(), any(), any());
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** deadline 뒤 기존 identity가 없으면 새 intent·HTTP·중단 없이 실패해야 한다. */
    @Test
    void expiredDeadlineWithoutIntentDoesNotCreateOrInterruptAttempt() throws Exception {
        Instant expiredAt = Instant.EPOCH.plusSeconds(6);
        StateProbePort probe = mock(StateProbePort.class);
        when(coordinator.interruptExisting(any(ProbeRunFence.class),
                any(ProbeAttemptIntent.class), anyString())).thenReturn(Optional.empty());

        StepExecutionResult result = executor(probe, clockAt(expiredAt))
                .execute(context(Instant.EPOCH, 1, "5s", "100ms"));
        StepExecutionResult recovered = resolve(result);

        assertThat(recovered).isInstanceOfSatisfying(StepExecutionResult.Failed.class,
                failed -> {
                    assertThat(failed.problemCode())
                            .isEqualTo(ProblemCode.PROBE_DEADLINE_EXCEEDED);
                    assertThat(failed.detailJson().path("attempts").asInt()).isEqualTo(1);
                    assertThat(failed.detailJson().path("requestNotStarted").asBoolean())
                            .isTrue();
                });
        verify(coordinator).interruptExisting(any(ProbeRunFence.class),
                any(ProbeAttemptIntent.class), anyString());
        verify(coordinator, never()).begin(any(), any(), any());
        verify(coordinator, never()).interrupt(any(), any(), any());
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** Profile이 POST를 허용해도 state Probe는 GET 외 method를 intent 전에 차단한다. */
    @Test
    void postProbeIsRejectedBeforeIntentEvenWhenProfileAllowsPost() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        StepExecutionContext context = context(
                Instant.EPOCH, 0, "30s", "100ms", List.of("GET", "POST"));
        ((tools.jackson.databind.node.ObjectNode) context.step().inputSnapshot()
                .path("request")).put("method", "POST");

        StepExecutionResult result = executor(probe, clockAt(Instant.EPOCH)).execute(context);

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.TARGET_METHOD_NOT_ALLOWED));
        verify(coordinator, never()).find(any());
        verify(coordinator, never()).begin(any(), any(), any());
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** intent와 시작 Evidence가 PostgreSQL과 같은 microsecond 요청 시각을 공유해야 한다. */
    @Test
    void beginNormalizesRequestedAtBeforeIntentAndEvidence() throws Exception {
        Instant clockInstant = Instant.ofEpochSecond(1, 123_456_789);
        StateProbePort probe = mock(StateProbePort.class);
        when(coordinator.begin(any(ProbeRunFence.class), any(ProbeAttemptIntent.class),
                any(AppendEvidenceCommand.class))).thenAnswer(invocation -> {
                    ProbeAttemptIntent candidate = invocation.getArgument(1);
                    return new ProbeAttemptStart(new ProbeAttemptIntent(
                            "attempt-1", candidate.runId(), candidate.stepId(),
                            candidate.attemptNumber(), candidate.requestedAt()),
                            ProbeAttemptStartState.EXISTING_FINALIZED);
                });

        executor(probe, clockAt(clockInstant))
                .execute(context(Instant.EPOCH, 0, "30s", "100ms"));

        ArgumentCaptor<ProbeAttemptIntent> intentCaptor =
                ArgumentCaptor.forClass(ProbeAttemptIntent.class);
        ArgumentCaptor<AppendEvidenceCommand> evidenceCaptor =
                ArgumentCaptor.forClass(AppendEvidenceCommand.class);
        verify(coordinator).begin(any(ProbeRunFence.class), intentCaptor.capture(),
                evidenceCaptor.capture());
        Instant expected = Instant.ofEpochSecond(1, 123_456_000);
        assertThat(intentCaptor.getValue().requestedAt()).isEqualTo(expected);
        assertThat(evidenceCaptor.getValue().observedAt()).isEqualTo(expected);
        assertThat(mapper.readTree(evidenceCaptor.getValue().payloadSummary())
                .path("requestedAt").asText()).isEqualTo(expected.toString());
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 완료 attempt는 권위 intent 시각을 사용하고 시작 Evidence를 중복 저장하지 않는다. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void completionUsesAuthoritativeIntentAndExcludesAttemptedEvidence() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        Instant authoritativeRequestedAt = Instant.EPOCH.plusMillis(1);
        ProbeAttemptIntent authoritative = intent("attempt-db", 1,
                authoritativeRequestedAt);
        when(coordinator.begin(any(ProbeRunFence.class), any(ProbeAttemptIntent.class),
                any(AppendEvidenceCommand.class)))
                .thenReturn(new ProbeAttemptStart(authoritative,
                        ProbeAttemptStartState.CREATED));
        when(probe.probe(any(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(observation("SUCCEEDED", Instant.EPOCH, Instant.EPOCH));

        StepExecutionResult result = executor(probe, clockAt(Instant.EPOCH))
                .execute(context(Instant.EPOCH, 0, "30s", "100ms"));
        StepExecutionResult.Deferred deferred = (StepExecutionResult.Deferred) result;
        deferred.persistence().run();

        ArgumentCaptor<ProbeAttempt> attemptCaptor =
                ArgumentCaptor.forClass(ProbeAttempt.class);
        ArgumentCaptor<ProbeRunFence> fenceCaptor =
                ArgumentCaptor.forClass(ProbeRunFence.class);
        ArgumentCaptor<List<AppendEvidenceCommand>> evidenceCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(coordinator).complete(fenceCaptor.capture(), attemptCaptor.capture(),
                evidenceCaptor.capture());
        assertThat(fenceCaptor.getValue().boundary()).isEqualTo(ProbeRunFence.Boundary.COMPLETE);
        assertThat(fenceCaptor.getValue().boundaryAt())
                .isEqualTo(authoritativeRequestedAt);
        assertThat(attemptCaptor.getValue().id()).isEqualTo("attempt-db");
        assertThat(attemptCaptor.getValue().requestedAt())
                .isEqualTo(authoritativeRequestedAt);
        assertThat(attemptCaptor.getValue().respondedAt())
                .isEqualTo(authoritativeRequestedAt);
        assertThat(evidenceCaptor.getValue())
                .extracting(AppendEvidenceCommand::type)
                .contains(EvidenceType.PROBE_RESPONSE_RECEIVED)
                .doesNotContain(EvidenceType.PROBE_ATTEMPTED);
    }

    /** 테스트용 권위 intent를 만든다. */
    private ProbeAttemptIntent intent(String id, int attemptNumber, Instant requestedAt) {
        return new ProbeAttemptIntent(id, "run-1", "step-1", attemptNumber, requestedAt);
    }

    /** 상태 문자열과 관측 시각을 가진 안전한 Probe 응답을 만든다. */
    private ProbeObservation observation(String status, Instant requestedAt, Instant respondedAt) {
        JsonNode body = mapper.createObjectNode().put("status", status);
        return new ProbeObservation(requestedAt, respondedAt, 200, "0".repeat(64), body, null);
    }

    /** 지정 시각에 고정된 실행 clock을 만든다. */
    private ClockProvider clockAt(Instant now) {
        return new ClockProvider() {
            /** 고정된 현재 시각을 반환한다. */
            @Override
            public Instant now() {
                return now;
            }

            /** 고정된 Java clock을 반환한다. */
            @Override
            public Clock clock() {
                return Clock.fixed(now, ZoneOffset.UTC);
            }
        };
    }

    /** mock 포트와 coordinator를 사용하는 Probe 실행기를 만든다. */
    private ProbeEventuallyStepExecutor executor(StateProbePort probe, ClockProvider clock) {
        return new ProbeEventuallyStepExecutor(
                probe, new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                coordinator, new AssertionEvaluator(), clock,
                new MetricsService(new SimpleMeterRegistry()));
    }

    /** Probe 복구 경계를 재현할 최소 Run·Plan·Step 문맥을 만든다. */
    private StepExecutionContext context(
            Instant waitStartedAt,
            int attemptCount,
            String within,
            String interval) throws Exception {
        return context(waitStartedAt, attemptCount, within, interval, List.of("GET"));
    }

    /** Profile 허용 method까지 지정해 Probe 복구 문맥을 만든다. */
    private StepExecutionContext context(
            Instant waitStartedAt,
            int attemptCount,
            String within,
            String interval,
            List<String> allowedMethods) throws Exception {
        return context(waitStartedAt, attemptCount, within, interval, allowedMethods, Map.of());
    }

    /** Profile secret header까지 지정해 Probe 복구 문맥을 만든다. */
    private StepExecutionContext context(
            Instant waitStartedAt,
            int attemptCount,
            String within,
            String interval,
            List<String> allowedMethods,
            Map<String, String> secretHeaders) throws Exception {
        ScenarioRun run = new ScenarioRun("run-1", "scenario", "1.0.0", "scenario-hash",
                "test-target", 1L, io.outcomeprobe.execution.RunStatus.RUNNING, null,
                JsonNodeFactory.instance.objectNode(), Instant.EPOCH.plusSeconds(60),
                Instant.EPOCH.plusSeconds(60), "worker-1", Instant.EPOCH.plusSeconds(60),
                0, 0, false, null, null,
                Instant.EPOCH, null, Instant.EPOCH);
        TargetProfile profile = new TargetProfile("test-target", "http://localhost:8091",
                List.of("/**"), allowedMethods, List.of(), List.of(), Map.of(), secretHeaders,
                Map.of(), List.of(), List.of());
        String sourceHash = HashValue.sha256("profile-yaml").hexValue();
        String profileHash = TargetProfileSafeSnapshot.calculateCanonicalHash(mapper,
                "outcomeprobe.io/v1alpha1", "test-target", "1.0.0", sourceHash, profile);
        TargetProfileSafeSnapshot profileSnapshot = new TargetProfileSafeSnapshot(
                "outcomeprobe.io/v1alpha1", "test-target", "1.0.0", sourceHash,
                profileHash, profile);
        CompiledRunPlan plan = new CompiledRunPlan("run-1", "scenario", "1.0.0",
                "scenario-hash", "contract", "1.0.0", "alias", "test-target", 1L,
                60_000L, Instant.EPOCH, Map.of(), List.of(),
                CompiledRunPlan.CURRENT_SCHEMA_VERSION, null, null, profileSnapshot, "set-hash");
        JsonNode input = mapper.readTree("""
                {
                  "within": "30s",
                  "interval": "100ms",
                  "stability": { "consecutiveSuccesses": 1, "interval": "100ms" },
                  "request": { "method": "GET", "path": "/test-support/payments/p1" },
                  "assertions": [ { "jsonPath": "$.status", "equals": "SUCCEEDED" } ]
                }
                """);
        ((tools.jackson.databind.node.ObjectNode) input).put("within", within);
        ((tools.jackson.databind.node.ObjectNode) input).put("interval", interval);
        RunStepExecution step = new RunStepExecution("step-1", "run-1", "probe-final", 0,
                StepType.PROBE_EVENTUALLY, StepStatus.WAITING, input, null, waitStartedAt,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(30), attemptCount, null, null, 0,
                Instant.EPOCH, null);
        return new StepExecutionContext(run, plan, step);
    }

    /** Transactional 결과를 RunEngine처럼 실행해 실제 Step 결과를 얻는다. */
    private StepExecutionResult resolve(StepExecutionResult result) {
        if (result instanceof StepExecutionResult.Transactional transactional) {
            return resolve(transactional.execution().get());
        }
        return result;
    }
}
