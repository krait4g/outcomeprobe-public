package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.emulator.ExpectedInvocation;
import io.outcomeprobe.emulator.ExpectedInvocationPort;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ProblemCode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * provider.expect Step을 실행해 ExpectedInvocation을 등록한다.
 *
 * <p>책임: Target이 호출할 operation과 commit·응답 장애·query visibility 계획을
 * Emulator가 조회할 수 있게 등록한다.</p>
 * <p>핵심 불변식: 등록은 멱등이다. 같은 Step이 재실행되면 기존 등록을 재사용해
 * expectation 횟수 계약이 이중으로 걸리지 않게 한다.</p>
 */
@Component
public class ProviderExpectStepExecutor implements StepExecutor {

    private final ExpectedInvocationPort expectedInvocationPort;

    /**
     * expectation 등록에 필요한 협업 객체를 주입한다.
     *
     * @param expectedInvocationPort expectation 포트
     */
    public ProviderExpectStepExecutor(ExpectedInvocationPort expectedInvocationPort) {
        this.expectedInvocationPort = expectedInvocationPort;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return PROVIDER_EXPECT
     */
    @Override
    public StepType supports() {
        return StepType.PROVIDER_EXPECT;
    }

    /**
     * operation·times·behavior를 읽어 expectation을 등록한다.
     *
     * @param context 실행 문맥
     * @return 완료 또는 구성 오류 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        JsonNode detail = context.step().inputSnapshot();
        String operation = detail.path("operation").asText(null);
        if (operation == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "provider.expect step에 operation이 없습니다.");
        }

        // 재시작 재실행 시 같은 step이 expectation을 중복 등록하지 않도록 기존 등록을 찾는다.
        Optional<ExpectedInvocation> existing = expectedInvocationPort
                .candidates(context.run().id(), operation).stream()
                .filter(invocation -> context.step().stepId().equals(invocation.stepId()))
                .findFirst();
        if (existing.isPresent()) {
            return new StepExecutionResult.Completed(null);
        }

        int minimum = 1;
        int maximum = 1;
        JsonNode times = detail.get("times");
        if (times != null) {
            if (times.isIntegralNumber()) {
                minimum = times.asInt();
                maximum = times.asInt();
            } else if (times.isObject()) {
                minimum = times.path("min").asInt(1);
                maximum = times.path("max").asInt(minimum);
            }
        }

        JsonNode matcher = detail.has("requestMatch")
                ? detail.get("requestMatch")
                : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode behavior = detail.has("behavior")
                ? detail.get("behavior")
                : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();

        expectedInvocationPort.register(new ExpectedInvocation(
                null,
                context.run().id(),
                context.step().stepId(),
                operation,
                context.step().ordinal(),
                matcher,
                behavior,
                minimum,
                maximum,
                0,
                0));
        return new StepExecutionResult.Completed(null);
    }
}
