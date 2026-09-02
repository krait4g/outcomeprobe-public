package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.evidence.EvidenceMatcher;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.definition.DslDurations;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * evidence.await Step을 실행해 특정 Evidence가 발생할 때까지 대기한다.
 *
 * <p>책임: Provider commit 같은 선행 사건이 관측될 때까지 Step을 WAITING으로
 * 유지하고, 발생하면 다음 Step으로 진행한다.</p>
 * <p>핵심 불변식: 대기는 thread를 점유하지 않는다. nextActionAt 기반 재평가만
 * 수행해 Worker가 다른 Run을 처리할 수 있게 한다.</p>
 */
@Component
public class EvidenceAwaitStepExecutor implements StepExecutor {

    private static final long POLL_INTERVAL_MS = 200L;

    private final EvidenceQueryPort evidenceQueryPort;
    private final ClockProvider clockProvider;

    /**
     * 대기 판정에 필요한 협업 객체를 주입한다.
     *
     * @param evidenceQueryPort Evidence 조회 포트
     * @param clockProvider 시각 계산용 clock
     */
    public EvidenceAwaitStepExecutor(EvidenceQueryPort evidenceQueryPort, ClockProvider clockProvider) {
        this.evidenceQueryPort = evidenceQueryPort;
        this.clockProvider = clockProvider;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return EVIDENCE_AWAIT
     */
    @Override
    public StepType supports() {
        return StepType.EVIDENCE_AWAIT;
    }

    /**
     * matcher 충족 여부를 검사하고 미충족 시 짧은 대기 후 재평가를 요청한다.
     *
     * @param context 실행 문맥
     * @return 완료·대기·실패 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        JsonNode detail = context.step().inputSnapshot();
        JsonNode match = detail.get("match");
        if (match == null || match.path("type").asText(null) == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "evidence.await step에 match.type이 필요합니다.");
        }
        EvidenceMatcher matcher = toMatcher(match);
        Duration within = DslDurations.parse(detail.path("within").asText("5s"));
        Instant now = clockProvider.now();
        Instant started = context.step().waitStartedAt() != null
                ? context.step().waitStartedAt()
                : now;
        Instant deadline = started.plus(within);

        // Timeline에 나중에 추가된 Evidence가 과거 await를 성공으로 뒤집지 않도록
        // 현재 전체가 아니라 이 Step의 관측 마감 시각까지로 조회 범위를 고정한다.
        if (evidenceQueryPort.existsObservedAtOrBefore(context.run().id(), matcher, deadline)) {
            return new StepExecutionResult.Completed(null);
        }

        if (now.isAfter(deadline)) {
            return new StepExecutionResult.Failed(ProblemCode.STEP_TIMEOUT_EXCEEDED,
                    "대기 시간 안에 Evidence가 발생하지 않았습니다: " + match.path("type").asText(), null);
        }
        return new StepExecutionResult.Waiting(now.plusMillis(POLL_INTERVAL_MS), null);
    }

    /**
     * DSL match 노드를 EvidenceMatcher로 변환한다.
     *
     * @param match {type, attributes} 구조 노드
     * @return 변환된 matcher
     */
    public static EvidenceMatcher toMatcher(JsonNode match) {
        EvidenceType type = EvidenceType.valueOf(match.path("type").asText());
        Map<String, String> attributes = new LinkedHashMap<>();
        JsonNode attributesNode = match.get("attributes");
        if (attributesNode != null && attributesNode.isObject()) {
            attributesNode.properties().forEach(entry ->
                    attributes.put(entry.getKey(), entry.getValue().asText()));
        }
        return new EvidenceMatcher(type, Map.copyOf(attributes));
    }
}
