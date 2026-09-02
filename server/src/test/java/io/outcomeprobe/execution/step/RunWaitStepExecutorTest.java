package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ClockProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * run.wait Step의 durable 대기 논리를 검증한다.
 *
 * <p>검출 가능한 결함: thread sleep으로 Worker를 점유하거나, 지연 시간 전에
 * 완료로 전이되는 문제. 첫 호출은 WAITING, 시간 경과 후 재평가에서만 Completed여야 한다.</p>
 */
class RunWaitStepExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 고정 시각 clock stub이다.
     */
    private static final class FixedClock implements ClockProvider {
        private Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
            this.now = now.plus(duration);
        }

        @Override
        /**
         * 고정된 현재 시각을 반환한다.
         */
        public Instant now() {
            return now;
        }

        @Override
        /**
         * 고정 clock을 반환한다.
         */
        public Clock clock() {
            return Clock.fixed(now, java.time.ZoneOffset.UTC);
        }
    }

    /**
     * 첫 호출은 지연 시각만큼 WAITING해야 한다.
     *
     * @throws Exception JSON 해석 실패 시 발생한다
     */
    @Test
    void firstCallWaits() throws Exception {
        FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
        RunWaitStepExecutor executor = new RunWaitStepExecutor(clock);

        StepExecutionResult result = executor.execute(context(null, "{\"wait\":\"2s\"}"));

        assertThat(result).isInstanceOf(StepExecutionResult.Waiting.class);
        StepExecutionResult.Waiting waiting = (StepExecutionResult.Waiting) result;
        assertThat(waiting.nextActionAt()).isEqualTo(Instant.parse("2026-01-01T00:00:02Z"));
    }

    /**
     * 지연 시간이 지난 뒤 재평가되면 Completed여야 한다.
     *
     * @throws Exception JSON 해석 실패 시 발생한다
     */
    @Test
    void completesAfterDurationElapses() throws Exception {
        FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
        RunWaitStepExecutor executor = new RunWaitStepExecutor(clock);

        // 첫 호출로 대기 시작 시각을 확정한다.
        StepExecutionResult first = executor.execute(context(null, "{\"wait\":\"2s\"}"));
        Instant waitStarted = ((StepExecutionResult.Waiting) first).nextActionAt().minusSeconds(2);

        // 지연 시간 이후로 시각을 옮긴 뒤 재평가한다.
        clock.advance(java.time.Duration.ofSeconds(3));
        StepExecutionResult second = executor.execute(context(waitStarted, "{\"wait\":\"2s\"}"));

        assertThat(second).isInstanceOf(StepExecutionResult.Completed.class);
    }

    /**
     * run.wait Step 실행 문맥을 조립한다.
     */
    private StepExecutionContext context(Instant waitStartedAt, String detailJson) throws Exception {
        ScenarioRun run = new ScenarioRun("run-1", "s", "1.0.0", "h", "target", 1L,
                RunStatus.RUNNING, null, null, Instant.now(), Instant.now().plusSeconds(60),
                null, null, 0, 0, false, null, null, Instant.now(), null, Instant.now());
        CompiledRunPlan plan = new CompiledRunPlan("run-1", "s", "1.0.0", "h", "c", "1.0.0",
                "alias", "target", 1L, 60_000L, Instant.now(), Map.of(), List.of());
        JsonNode detail = mapper.readTree(detailJson);
        RunStepExecution step = new RunStepExecution("step-1", "run-1", "wait-step", 0,
                StepType.RUN_WAIT, io.outcomeprobe.execution.StepStatus.RUNNING, detail, null,
                waitStartedAt, Instant.now(), Instant.now().plusSeconds(60), 0, null, null,
                0, Instant.now(), null);
        return new StepExecutionContext(run, plan, step);
    }
}
