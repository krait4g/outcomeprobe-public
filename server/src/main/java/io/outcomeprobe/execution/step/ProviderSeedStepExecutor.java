package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.emulator.ProviderResource;
import io.outcomeprobe.emulator.ProviderStatePort;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.foundation.ProblemCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * provider.seed Step을 실행해 Provider truth fixture를 생성한다.
 *
 * <p>책임: Scenario가 가정한 초기 Provider 상태를 Run 시작 시 확정해, 이후
 * operation commit이 전이 검증을 할 기준을 만든다.</p>
 * <p>핵심 불변식: 재시작으로 같은 Step이 다시 실행돼도 기존 자원이 있으면
 * 재생성하지 않는다. 이로써 side effect 중복 없이 durable 재개가 가능하다.</p>
 */
@Component
public class ProviderSeedStepExecutor implements StepExecutor {

    private final ProviderStatePort providerStatePort;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;

    /**
     * seed에 필요한 협업 객체를 주입한다.
     *
     * @param providerStatePort Provider 상태 포트
     * @param idGenerator 행 식별자 생성기
     * @param clockProvider 생성 시각 제공자
     */
    public ProviderSeedStepExecutor(ProviderStatePort providerStatePort,
                                    IdGenerator idGenerator, ClockProvider clockProvider) {
        this.providerStatePort = providerStatePort;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return PROVIDER_SEED
     */
    @Override
    public StepType supports() {
        return StepType.PROVIDER_SEED;
    }

    /**
     * resource 정의를 읽어 Provider truth를 seed한다.
     *
     * <p>왜 멱등인가: process crash 후 같은 Step이 재실행될 수 있으며, 중복 seed는
     * version·상태를 초기화해 Scenario 전제를 깨뜨린다.</p>
     *
     * @param context 실행 문맥
     * @return 완료 또는 구성 오류 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        JsonNode detail = context.step().inputSnapshot();
        JsonNode resource = detail.get("resource");
        if (resource == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "provider.seed step에 resource가 없습니다.");
        }
        String type = resource.path("type").asText(null);
        String id = resource.path("id").asText(null);
        String state = resource.path("state").asText(null);
        if (type == null || id == null || state == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "resource의 type·id·state가 필요합니다.");
        }

        // 이미 seed된 자원은 재생성하지 않아 재시작 재실행에서 상태 초기화를 막는다.
        if (providerStatePort.find(context.run().id(), type, id).isPresent()) {
            return new StepExecutionResult.Completed(null);
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        JsonNode attributesNode = resource.get("attributes");
        if (attributesNode != null && attributesNode.isObject()) {
            attributesNode.properties().forEach(entry ->
                    attributes.put(entry.getKey(), toPlain(entry.getValue())));
        }

        providerStatePort.seed(new ProviderResource(
                idGenerator.nextId(),
                context.run().id(),
                type,
                id,
                state,
                state,
                resource.path("version").asLong(0),
                0,
                null,
                attributes,
                clockProvider.now()));
        return new StepExecutionResult.Completed(null);
    }

    /**
     * JSON 노드를 도메인이 사용하는 일반 값(문자열·숫자·boolean)으로 변환한다.
     */
    private Object toPlain(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }
}
