package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.execution.step.ProbeEventuallyStepExecutor;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetHeaderResolver;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Probe stability와 상태 회귀 방지를 검증한다 (OP-AUDIT-PRB-001/002).
 *
 * <p>검증 원칙: 연속 성공 요구 횟수(consecutiveSuccesses)를 채우기 전에는
 * PASS하지 않고, 중간에 mismatch가 나오면 연속 카운트가 reset된다.</p>
 */
class ProbeStabilityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> probeResponses = new java.util.ArrayList<>();
    private int probeIndex = 0;

    private final StateProbePort stubProbe = (profile, method, path, headers, timeout) ->
            new ProbeObservation(Instant.EPOCH, Instant.EPOCH, 200,
                    "0".repeat(64), probeResponses.get(probeIndex++), null);

    private final ClockProvider fixedClock = new ClockProvider() {
        @Override
        /**
         * 고정 시각을 반환한다.
         */
        public Instant now() {
            return Instant.EPOCH;
        }

        @Override
        /**
         * 고정 clock을 반환한다.
         */
        public Clock clock() {
            return Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        }
    };

    private final ProbeAttemptTransactionCoordinator attemptCoordinator =
            mock(ProbeAttemptTransactionCoordinator.class);
    private final AtomicInteger attemptIds = new AtomicInteger();

    /** 각 테스트에서 신규 intent가 생성되는 기본 coordinator 동작을 준비한다. */
    @BeforeEach
    void prepareAttemptCoordinator() {
        when(attemptCoordinator.begin(any(ProbeRunFence.class),
                any(ProbeAttemptIntent.class), any(AppendEvidenceCommand.class)))
                .thenAnswer(invocation -> {
                    ProbeAttemptIntent candidate = invocation.getArgument(1);
                    ProbeAttemptIntent authoritative = new ProbeAttemptIntent(
                            "attempt-" + attemptIds.incrementAndGet(), candidate.runId(),
                            candidate.stepId(), candidate.attemptNumber(), candidate.requestedAt());
                    return new ProbeAttemptStart(authoritative, ProbeAttemptStartState.CREATED);
                });
    }

    /**
     * SUCCEEDED→PROCESSING→SUCCEEDED×2 순서에서 연속 2회 성공 후에만 PASS해야 한다.
     *
     * @throws Exception JSON 해석 실패 시 발생한다
     */
    @Test
    void resetsConsecutiveCountOnMismatch() throws Exception {
        probeResponses.add(mapper.readTree("{\"status\":\"SUCCEEDED\"}"));
        probeResponses.add(mapper.readTree("{\"status\":\"PROCESSING\"}"));
        probeResponses.add(mapper.readTree("{\"status\":\"SUCCEEDED\"}"));
        probeResponses.add(mapper.readTree("{\"status\":\"SUCCEEDED\"}"));

        ProbeEventuallyStepExecutor executor = new ProbeEventuallyStepExecutor(
                stubProbe, new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                attemptCoordinator, new AssertionEvaluator(), fixedClock,
                new io.outcomeprobe.infrastructure.metrics.MetricsService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        JsonNode lastObservation = null;
        StepExecutionResult result = null;
        for (int i = 0; i < 4; i++) {
            result = resolve(executor.execute(context(lastObservation)));
            if (result instanceof StepExecutionResult.Waiting waiting) {
                lastObservation = waiting.observation();
            }
        }

        // 4회 Probe(연속 성공 2회 충족) 후에만 Completed여야 한다.
        assertThat(result).isInstanceOf(StepExecutionResult.Completed.class);
    }

    /**
     * 연속 성공 요구치를 채우지 못하면 조기 PASS하지 않는다.
     *
     * @throws Exception JSON 해석 실패 시 발생한다
     */
    @Test
    void doesNotPassBeforeStabilityWindow() throws Exception {
        probeResponses.add(mapper.readTree("{\"status\":\"SUCCEEDED\"}"));

        ProbeEventuallyStepExecutor executor = new ProbeEventuallyStepExecutor(
                stubProbe, new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                attemptCoordinator, new AssertionEvaluator(), fixedClock,
                new io.outcomeprobe.infrastructure.metrics.MetricsService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        StepExecutionResult result = resolve(executor.execute(context(null)));

        // 1회 성공만으로는 consecutiveSuccesses=2를 충족하지 못해 Waiting이어야 한다.
        assertThat(result).isInstanceOf(StepExecutionResult.Waiting.class);
    }

    /** Profile secret이 누락되면 Probe 포트를 한 번도 호출하지 않고 즉시 ERROR여야 한다. */
    @Test
    void missingSecretNeverCallsProbePort() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeEventuallyStepExecutor executor = new ProbeEventuallyStepExecutor(
                probe, new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                attemptCoordinator, new AssertionEvaluator(), fixedClock,
                new io.outcomeprobe.infrastructure.metrics.MetricsService(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        StepExecutionResult result = executor.execute(
                context(null, Map.of("Authorization", "missing-auth")));

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode()).isEqualTo(ProblemCode.TARGET_SECRET_UNRESOLVED));
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** assertion이 비어 있으면 HTTP 2xx만으로 통과하지 않고 schema error여야 한다. */
    @Test
    void emptyAssertionsFailBeforeProbeCall() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeEventuallyStepExecutor executor = new ProbeEventuallyStepExecutor(
                probe, new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                attemptCoordinator, new AssertionEvaluator(), fixedClock,
                new io.outcomeprobe.infrastructure.metrics.MetricsService(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        StepExecutionResult result = executor.execute(context(null, Map.of(), false));

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.SCENARIO_SCHEMA_INVALID));
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 잘못된 JSONPath는 Target 실패가 아니라 HTTP 전 Scenario 오류여야 한다. */
    @Test
    void invalidJsonPathFailsBeforeProbeCall() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeEventuallyStepExecutor executor = executor(probe, fixedClock);
        StepExecutionContext context = context(null);
        ((tools.jackson.databind.node.ObjectNode) context.step().inputSnapshot()
                .path("assertions").get(0)).put("jsonPath", "$[?");

        StepExecutionResult result = executor.execute(context);

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.SCENARIO_SCHEMA_INVALID));
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 0ms interval은 busy loop를 만들기 전에 Scenario 오류로 거부해야 한다. */
    @Test
    void zeroIntervalFailsBeforeProbeCall() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeEventuallyStepExecutor executor = executor(probe, fixedClock);
        StepExecutionContext context = context(null);
        ((tools.jackson.databind.node.ObjectNode) context.step().inputSnapshot())
                .put("interval", "0ms");

        StepExecutionResult result = executor.execute(context);

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.SCENARIO_SCHEMA_INVALID));
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 명세상 2,000회 상한에 도달한 Step은 추가 HTTP를 보내지 않아야 한다. */
    @Test
    void attemptLimitFailsBeforeProbeCall() throws Exception {
        StateProbePort probe = mock(StateProbePort.class);
        ProbeEventuallyStepExecutor executor = executor(probe, fixedClock);
        StepExecutionContext context = context(null, Map.of(), true,
                Instant.EPOCH, 2_000, "30s");

        StepExecutionResult result = executor.execute(context);

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.SCENARIO_SCHEMA_INVALID));
        verify(probe, never()).probe(any(), anyString(), anyString(), anyMap(), any());
    }

    /** 첫 HTTP 소요 시간 뒤에도 within 시작점이 commit 시각으로 이동하면 안 된다. */
    @Test
    void preservesFirstProbeStartAcrossWaitingCommit() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        AtomicInteger calls = new AtomicInteger();
        ClockProvider dynamicClock = new ClockProvider() {
            /** 테스트에서 이동 가능한 현재 시각을 반환한다. */
            @Override
            public Instant now() {
                return now.get();
            }

            /** 현재 테스트 시각에 고정된 Clock을 반환한다. */
            @Override
            public Clock clock() {
                return Clock.fixed(now.get(), ZoneOffset.UTC);
            }
        };
        StateProbePort slowProbe = (profile, method, path, headers, timeout) -> {
            if (calls.getAndIncrement() == 0) {
                now.set(Instant.EPOCH.plusMillis(900));
                return new ProbeObservation(Instant.EPOCH, now.get(), 200, "a".repeat(64),
                        mapper.createObjectNode().put("status", "PROCESSING"), null);
            }
            return new ProbeObservation(now.get(), now.get(), 200, "b".repeat(64),
                    mapper.createObjectNode().put("status", "SUCCEEDED"), null);
        };
        ProbeEventuallyStepExecutor executor = executor(slowProbe, dynamicClock);
        StepExecutionContext firstContext = context(
                null, Map.of(), true, null, 0, "1s");
        ((tools.jackson.databind.node.ObjectNode) firstContext.step().inputSnapshot()
                .path("stability")).put("consecutiveSuccesses", 1);

        StepExecutionResult first = resolve(executor.execute(firstContext));
        assertThat(first).isInstanceOf(StepExecutionResult.Waiting.class);
        JsonNode lastObservation = ((StepExecutionResult.Waiting) first).observation();

        now.set(Instant.EPOCH.plusMillis(1_100));
        StepExecutionContext secondContext = context(lastObservation, Map.of(), true,
                Instant.EPOCH.plusMillis(900), 1, "1s");
        ((tools.jackson.databind.node.ObjectNode) secondContext.step().inputSnapshot()
                .path("stability")).put("consecutiveSuccesses", 1);
        StepExecutionResult second = resolve(executor.execute(secondContext));

        assertThat(lastObservation.path("probeStartedAt").asText())
                .isEqualTo(Instant.EPOCH.toString());
        assertThat(second).isInstanceOfSatisfying(StepExecutionResult.Failed.class,
                failed -> assertThat(failed.problemCode())
                        .isEqualTo(ProblemCode.PROBE_DEADLINE_EXCEEDED));
    }

    /**
     * Probe Step 실행 문맥을 조립한다. lastObservation으로 stability 상태를 전달한다.
     */
    private StepExecutionContext context(JsonNode lastObservation) throws Exception {
        return context(lastObservation, Map.of());
    }

    /** secret header 구성을 포함해 Probe Step 실행 문맥을 조립한다. */
    private StepExecutionContext context(JsonNode lastObservation,
                                         Map<String, String> secretHeaders) throws Exception {
        return context(lastObservation, secretHeaders, true);
    }

    /** assertions 포함 여부를 지정해 Probe Step 실행 문맥을 조립한다. */
    private StepExecutionContext context(JsonNode lastObservation,
                                         Map<String, String> secretHeaders,
                                         boolean includeAssertions) throws Exception {
        int attemptCount = lastObservation == null
                ? 0 : lastObservation.path("attempts").asInt(0);
        return context(lastObservation, secretHeaders, includeAssertions,
                Instant.EPOCH, attemptCount, "30s");
    }

    /** wait 시작·attempt·within을 지정해 재개 경계 테스트 문맥을 만든다. */
    private StepExecutionContext context(JsonNode lastObservation,
                                         Map<String, String> secretHeaders,
                                         boolean includeAssertions,
                                         Instant waitStartedAt,
                                         int attemptCount,
                                         String within) throws Exception {
        ScenarioRun run = new ScenarioRun("run-1", "s", "1.0.0", "h", "test-target", 1L,
                io.outcomeprobe.execution.RunStatus.RUNNING, null,
                JsonNodeFactory.instance.objectNode(), Instant.EPOCH.plusSeconds(60),
                Instant.EPOCH.plusSeconds(60), "worker-1", Instant.EPOCH.plusSeconds(60),
                0, 0, false, null, null,
                Instant.EPOCH, null, Instant.EPOCH);
        TargetProfile profile = new TargetProfile("test-target", "http://localhost:8091",
                List.of("/**"), List.of("GET"), List.of(), List.of(),
                Map.of(), secretHeaders, Map.of(), List.of(), List.of());
        String sourceHash = io.outcomeprobe.foundation.HashValue.sha256("profile-yaml").hexValue();
        String profileHash = TargetProfileSafeSnapshot.calculateCanonicalHash(mapper,
                "outcomeprobe.io/v1alpha1", "test-target", "1.0.0", sourceHash, profile);
        TargetProfileSafeSnapshot profileSnapshot = new TargetProfileSafeSnapshot(
                "outcomeprobe.io/v1alpha1", "test-target", "1.0.0", sourceHash,
                profileHash, profile);
        CompiledRunPlan plan = new CompiledRunPlan("run-1", "s", "1.0.0", "h", "c", "1.0.0",
                "alias", "test-target", 1L, 60_000L, Instant.EPOCH, Map.of(), List.of(),
                CompiledRunPlan.CURRENT_SCHEMA_VERSION, null, null, profileSnapshot, "set-hash");
        JsonNode input = mapper.readTree("""
                {
                  "within": "30s",
                  "interval": "100ms",
                  "stability": { "consecutiveSuccesses": 2, "interval": "100ms" },
                  "request": { "method": "GET", "path": "/test-support/payments/p1" },
                  "assertions": [ { "jsonPath": "$.status", "equals": "SUCCEEDED" } ]
                }
                """);
        ((tools.jackson.databind.node.ObjectNode) input).put("within", within);
        if (!includeAssertions) {
            ((tools.jackson.databind.node.ObjectNode) input).putArray("assertions");
        }
        RunStepExecution step = new RunStepExecution("step-1", "run-1", "assert-final", 0,
                StepType.PROBE_EVENTUALLY, StepStatus.WAITING, input, null,
                waitStartedAt, Instant.EPOCH, Instant.EPOCH.plusSeconds(30), attemptCount,
                lastObservation, null, 0, Instant.EPOCH, null);
        return new StepExecutionContext(run, plan, step);
    }

    /** 지정 Probe·clock으로 실행기를 만든다. */
    private ProbeEventuallyStepExecutor executor(StateProbePort probe, ClockProvider clock) {
        return new ProbeEventuallyStepExecutor(
                probe, new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                attemptCoordinator, new AssertionEvaluator(), clock,
                new io.outcomeprobe.infrastructure.metrics.MetricsService(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    /** RunEngine처럼 deferred DB 작업을 실행한 뒤 실제 Step 결과를 반환한다. */
    private StepExecutionResult resolve(StepExecutionResult result) {
        if (result instanceof StepExecutionResult.Transactional transactional) {
            return resolve(transactional.execution().get());
        }
        if (result instanceof StepExecutionResult.Deferred deferred) {
            deferred.persistence().run();
            deferred.afterCommit().run();
            return deferred.result();
        }
        return result;
    }
}
