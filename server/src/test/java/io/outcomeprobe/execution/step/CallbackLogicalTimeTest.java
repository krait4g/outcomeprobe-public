package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;

import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.StepType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Callback logical time이 Run 시작 상대 계획 시각인지 검증한다. */
class CallbackLogicalTimeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 선행 run.wait 2초와 callback delay 300ms는 logical time 2,300ms가 된다. */
    @Test
    void includesPriorDeterministicWaits() throws Exception {
        CompiledRunPlan plan = new CompiledRunPlan(
                "run-1", "scenario", "1.0.0", "hash", "contract", "1.0.0",
                "alias", "profile", 1L, 10_000L, Instant.EPOCH, Map.of(),
                List.of(
                        new CompiledRunPlan.CompiledStep("wait-before", StepType.RUN_WAIT, 0,
                                objectMapper.readTree("{\"wait\":\"2s\"}")),
                        new CompiledRunPlan.CompiledStep("callback", StepType.CALLBACK_SCHEDULE, 1,
                                objectMapper.createObjectNode()),
                        new CompiledRunPlan.CompiledStep("wait-after", StepType.RUN_WAIT, 2,
                                objectMapper.readTree("{\"duration\":\"5s\"}"))));

        long logicalTime = CallbackScheduleStepExecutor.plannedLogicalTimeMs(plan, 1, 300L);

        assertThat(logicalTime).isEqualTo(2_300L);
    }
}
