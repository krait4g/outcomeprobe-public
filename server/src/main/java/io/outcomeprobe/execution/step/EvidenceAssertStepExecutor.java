package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceMatcher;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ProblemCode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * evidence.assert Step을 실행해 Timeline의 횟수·순서·부재를 판정한다.
 *
 * <p>책임: Provider commit 횟수, Callback 전달 횟수, 사건 간 순서 같은 상호작용
 * 증거가 Scenario 기대와 일치하는지 최종 확인한다.</p>
 * <p>핵심 불변식: 이 판정은 Evidence append-only Timeline에만 근거한다.
 * HTTP 응답이 아니라 실제로 기록된 사건만 증거로 인정한다.</p>
 */
@Component
public class EvidenceAssertStepExecutor implements StepExecutor {

    private final EvidenceQueryPort evidenceQueryPort;
    private final EvidenceRecorder evidenceRecorder;

    /**
     * 증거 판정에 필요한 협업 객체를 주입한다.
     *
     * @param evidenceQueryPort Evidence 조회 포트
     * @param evidenceRecorder 판정 결과 기록기
     */
    public EvidenceAssertStepExecutor(EvidenceQueryPort evidenceQueryPort,
                                      EvidenceRecorder evidenceRecorder) {
        this.evidenceQueryPort = evidenceQueryPort;
        this.evidenceRecorder = evidenceRecorder;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return EVIDENCE_ASSERT
     */
    @Override
    public StepType supports() {
        return StepType.EVIDENCE_ASSERT;
    }

    /**
     * assertion 목록을 전부 평가해 Timeline 증거를 판정한다.
     *
     * @param context 실행 문맥
     * @return 완료 또는 실패 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        JsonNode detail = context.step().inputSnapshot();
        JsonNode assertions = detail.get("assertions");
        if (assertions == null || !assertions.isArray()) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "evidence.assert step에 assertions 배열이 필요합니다.");
        }

        ObjectNode failures = JsonNodeFactory.instance.objectNode();
        boolean allPassed = true;
        int index = 0;
        for (JsonNode assertion : assertions) {
            String failure = evaluateOne(context.run().id(), assertion);
            if (failure != null) {
                allPassed = false;
                failures.put("assertion[" + index + "]", failure);
            }
            index++;
        }

        if (allPassed) {
            evidenceRecorder.append(AppendEvidenceCommand
                    .of(context.run().id(), EvidenceType.EVIDENCE_ASSERTION_PASSED, EvidenceSource.ASSERTION)
                    .withCorrelation(context.step().stepId(), null, null));
            return new StepExecutionResult.Completed(null);
        }
        evidenceRecorder.append(AppendEvidenceCommand
                .of(context.run().id(), EvidenceType.EVIDENCE_ASSERTION_FAILED, EvidenceSource.ASSERTION)
                .withCorrelation(context.step().stepId(), null, null)
                .withPayload(failures.toString(), null, false));
        return new StepExecutionResult.Failed(ProblemCode.EVIDENCE_ASSERTION_FAILED,
                "Evidence assertion이 기대와 다릅니다.", failures);
    }

    /**
     * Evidence assertion 하나를 평가해 실패 사유를 반환한다. 통과 시 null이다.
     */
    private String evaluateOne(String runId, JsonNode assertion) {
        if (assertion.has("count")) {
            EvidenceMatcher matcher = EvidenceAwaitStepExecutor.toMatcher(assertion.get("count"));
            long actual = evidenceQueryPort.count(runId, matcher);
            if (assertion.has("equals") && assertion.get("equals").asLong() != actual) {
                return "count expected " + assertion.get("equals").asLong() + " but was " + actual;
            }
            if (assertion.has("greaterThanOrEqual")
                    && actual < assertion.get("greaterThanOrEqual").asLong()) {
                return "count expected >= " + assertion.get("greaterThanOrEqual").asLong()
                        + " but was " + actual;
            }
            if (assertion.has("lessThanOrEqual")
                    && actual > assertion.get("lessThanOrEqual").asLong()) {
                return "count expected <= " + assertion.get("lessThanOrEqual").asLong()
                        + " but was " + actual;
            }
            return null;
        }
        if (assertion.has("absent")) {
            EvidenceMatcher matcher = EvidenceAwaitStepExecutor.toMatcher(assertion.get("absent"));
            if (evidenceQueryPort.exists(runId, matcher)) {
                return "expected absent but found: " + matcher.type();
            }
            return null;
        }
        if (assertion.has("before")) {
            JsonNode before = assertion.get("before");
            EvidenceMatcher first = EvidenceAwaitStepExecutor.toMatcher(before.get("first"));
            EvidenceMatcher second = EvidenceAwaitStepExecutor.toMatcher(before.get("second"));
            Optional<Long> firstSeq = evidenceQueryPort.firstSequenceOf(runId, first);
            Optional<Long> secondSeq = evidenceQueryPort.firstSequenceOf(runId, second);
            if (firstSeq.isEmpty() || secondSeq.isEmpty()) {
                return "before comparison requires both events: first="
                        + firstSeq.isPresent() + " second=" + secondSeq.isPresent();
            }
            if (firstSeq.get() >= secondSeq.get()) {
                return "expected " + first.type() + " before " + second.type();
            }
            return null;
        }
        return "지원하지 않는 evidence assertion 형식입니다.";
    }
}
