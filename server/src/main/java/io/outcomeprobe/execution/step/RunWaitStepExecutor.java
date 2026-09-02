package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.ProblemCode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * run.wait Step을 실행해 의도적인 논리 시간 지연을 만든다.
 *
 * <p>책임: Scenario가 지정한 시간만큼 Run 진행을 미룬다. Callback 지연·Probe
 * 타이밍을 조절해 시간 의존 시나리오를 재현한다.</p>
 * <p>핵심 불변식: thread sleep으로 대기하지 않는다. WAITING으로 전환해
 * nextActionAt에 재 lease되므로 Worker는 대기 중 다른 Run을 처리한다(§7.6 durable).</p>
 */
@Component
public class RunWaitStepExecutor implements StepExecutor {

    private final ClockProvider clockProvider;

    /**
     * 대기 시각 계산에 필요한 clock을 주입한다.
     *
     * @param clockProvider 시각 제공자
     */
    public RunWaitStepExecutor(ClockProvider clockProvider) {
        this.clockProvider = clockProvider;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return RUN_WAIT
     */
    @Override
    public StepType supports() {
        return StepType.RUN_WAIT;
    }

    /**
     * 처음 호출되면 지연 시각만큼 WAITING하고, 재평가 시 시간이 지났으면 완료한다.
     *
     * <p>왜 두 단계인가: Worker가 지연 시간 동안 thread를 점유하지 않아야 하므로
     * 첫 호출은 WAITING으로 thread를 해제하고, nextActionAt 이후 재 lease돼서야
     * 완료로 전이한다.</p>
     *
     * @param context 실행 문맥
     * @return 대기 또는 완료 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        JsonNode detail = context.step().inputSnapshot();
        Duration duration = parseDuration(detail);

        Instant waitStarted = context.step().waitStartedAt();
        Instant now = clockProvider.now();

        // 첫 호출: 대기 시작을 표시하고 지연 시각 이후로 재 lease를 요청한다.
        if (waitStarted == null) {
            return new StepExecutionResult.Waiting(now.plus(duration), null);
        }

        // 재평가: 논리 지연 시간이 지났으면 완료한다.
        if (!now.isBefore(waitStarted.plus(duration))) {
            return new StepExecutionResult.Completed(null);
        }

        // lease가 일찍 일어난 경우 남은 시간만큼 다시 대기한다.
        return new StepExecutionResult.Waiting(waitStarted.plus(duration), null);
    }

    /**
     * detail에서 wait·duration 필드를 Duration으로 해석한다.
     */
    private Duration parseDuration(JsonNode detail) {
        String raw = null;
        if (detail.has("wait")) {
            raw = detail.get("wait").asText();
        } else if (detail.has("duration")) {
            raw = detail.get("duration").asText();
        }
        if (raw == null) {
            throw new io.outcomeprobe.scenario.ArtifactImportException(
                    ProblemCode.SCENARIO_SCHEMA_INVALID, "wait",
                    "run.wait step에 wait 또는 duration 필드가 필요합니다.");
        }
        return io.outcomeprobe.scenario.definition.DslDurations.parse(raw);
    }
}
