package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.ProblemCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** evidence.await의 Step 제한시간과 Run 전역 제한시간 문제 코드를 구분해 검증한다. */
class EvidenceAwaitStepExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvidenceQueryPort evidenceQueryPort = mock(EvidenceQueryPort.class);
    private final ClockProvider clockProvider = mock(ClockProvider.class);

    /** within을 초과한 Step은 전역 Run deadline 코드가 아닌 Step 전용 코드를 반환해야 한다. */
    @Test
    void expiredWaitUsesStepTimeoutProblemCode() {
        when(clockProvider.now()).thenReturn(NOW);
        when(evidenceQueryPort.existsObservedAtOrBefore(eq("run-1"), any(), any()))
                .thenReturn(false);
        EvidenceAwaitStepExecutor executor =
                new EvidenceAwaitStepExecutor(evidenceQueryPort, clockProvider);

        StepExecutionResult result = executor.execute(context(NOW.minusSeconds(6)));

        assertThat(result).isInstanceOf(StepExecutionResult.Failed.class);
        StepExecutionResult.Failed failed = (StepExecutionResult.Failed) result;
        assertThat(failed.problemCode()).isEqualTo(ProblemCode.STEP_TIMEOUT_EXCEEDED);
        assertThat(failed.problemCode()).isNotEqualTo(ProblemCode.SCENARIO_TIMEOUT_EXCEEDED);
        verify(evidenceQueryPort).existsObservedAtOrBefore(
                eq("run-1"), any(), eq(NOW.minusSeconds(1)));
    }

    /** within 안에서 Evidence가 없으면 Step은 실패하지 않고 다음 polling을 예약해야 한다. */
    @Test
    void unexpiredWaitKeepsPolling() {
        when(clockProvider.now()).thenReturn(NOW);
        when(evidenceQueryPort.existsObservedAtOrBefore(eq("run-1"), any(), any()))
                .thenReturn(false);
        EvidenceAwaitStepExecutor executor =
                new EvidenceAwaitStepExecutor(evidenceQueryPort, clockProvider);

        StepExecutionResult result = executor.execute(context(NOW.minusSeconds(4)));

        assertThat(result).isInstanceOf(StepExecutionResult.Waiting.class);
        StepExecutionResult.Waiting waiting = (StepExecutionResult.Waiting) result;
        assertThat(waiting.nextActionAt()).isEqualTo(NOW.plusMillis(200));
    }

    /** deadline 뒤에 추가된 일치 Evidence는 만료된 Step을 성공으로 뒤집지 못해야 한다. */
    @Test
    void evidenceObservedAfterDeadlineDoesNotCompleteExpiredWait() {
        Instant deadline = NOW.minusSeconds(1);
        Instant observedAfterDeadline = deadline.plusMillis(1);
        when(clockProvider.now()).thenReturn(NOW);
        when(evidenceQueryPort.existsObservedAtOrBefore(eq("run-1"), any(), eq(deadline)))
                .thenAnswer(invocation -> !observedAfterDeadline.isAfter(
                        invocation.getArgument(2, Instant.class)));
        EvidenceAwaitStepExecutor executor =
                new EvidenceAwaitStepExecutor(evidenceQueryPort, clockProvider);

        StepExecutionResult result = executor.execute(context(NOW.minusSeconds(6)));

        assertThat(result).isInstanceOf(StepExecutionResult.Failed.class);
        verify(evidenceQueryPort).existsObservedAtOrBefore(
                eq("run-1"), any(), eq(deadline));
    }

    /** poll이 늦어졌더라도 deadline 이전에 관측된 Evidence는 정상 완료로 인정해야 한다. */
    @Test
    void evidenceObservedBeforeDeadlineCompletesEvenWhenPolledAfterDeadline() {
        Instant deadline = NOW.minusSeconds(1);
        Instant observedBeforeDeadline = deadline.minusMillis(1);
        when(clockProvider.now()).thenReturn(NOW);
        when(evidenceQueryPort.existsObservedAtOrBefore(eq("run-1"), any(), eq(deadline)))
                .thenAnswer(invocation -> !observedBeforeDeadline.isAfter(
                        invocation.getArgument(2, Instant.class)));
        EvidenceAwaitStepExecutor executor =
                new EvidenceAwaitStepExecutor(evidenceQueryPort, clockProvider);

        StepExecutionResult result = executor.execute(context(NOW.minusSeconds(6)));

        assertThat(result).isInstanceOf(StepExecutionResult.Completed.class);
        verify(evidenceQueryPort).existsObservedAtOrBefore(
                eq("run-1"), any(), eq(deadline));
    }

    /** 테스트용 Run·plan·evidence.await Step snapshot을 만든다. */
    private StepExecutionContext context(Instant waitStartedAt) {
        ScenarioRun run = new ScenarioRun(
                "run-1", "scenario", "1.0.0", "scenario-hash", "target", 1L,
                RunStatus.RUNNING, 0, null, NOW, NOW.plusSeconds(60), null, null,
                0, 0, false, null, null, NOW.minusSeconds(10), null,
                NOW.minusSeconds(10));
        CompiledRunPlan plan = new CompiledRunPlan(
                "run-1", "scenario", "1.0.0", "scenario-hash", "contract", "1.0.0",
                "alias", "target", 1L, 60_000L, NOW.minusSeconds(10), Map.of(),
                List.of());
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("within", "5s");
        ObjectNode match = detail.putObject("match");
        match.put("type", EvidenceType.PROVIDER_OPERATION_COMMITTED.name());
        match.putObject("attributes").put("operation", "charge");
        RunStepExecution step = new RunStepExecution(
                "step-row-1", "run-1", "await-provider-commit", 0,
                StepType.EVIDENCE_AWAIT, StepStatus.WAITING, detail, null,
                waitStartedAt, NOW, NOW.plusSeconds(60), 1, null, null, 0,
                waitStartedAt, null);
        return new StepExecutionContext(run, plan, step);
    }
}
