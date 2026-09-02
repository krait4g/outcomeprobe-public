package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.outcomeprobe.emulator.ProviderResource;
import io.outcomeprobe.emulator.ProviderStatePort;
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
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * provider.assert Step을 실행해 Provider truth 상태와 호출 횟수를 판정한다.
 *
 * <p>책임: Target 관점이 아니라 Provider Emulator 내부 truth가 기대대로
 * commit됐는지 확인한다. resource의 canonical 상태와 operation commit 횟수를
 * 단언한다.</p>
 * <p>핵심 불변식: 이 판정은 Provider truth와 commit Evidence에만 근거한다.
 * Target이 받은 응답이나 Target 상태는 보지 않는다(§7.6).</p>
 */
@Component
public class ProviderAssertStepExecutor implements StepExecutor {

    private final ProviderStatePort providerStatePort;
    private final EvidenceQueryPort evidenceQueryPort;
    private final EvidenceRecorder evidenceRecorder;

    /**
     * Provider 판정에 필요한 협업 객체를 주입한다.
     *
     * @param providerStatePort Provider truth 조회 포트
     * @param evidenceQueryPort commit Evidence 조회 포트
     * @param evidenceRecorder 판정 결과 기록기
     */
    public ProviderAssertStepExecutor(ProviderStatePort providerStatePort,
                                      EvidenceQueryPort evidenceQueryPort,
                                      EvidenceRecorder evidenceRecorder) {
        this.providerStatePort = providerStatePort;
        this.evidenceQueryPort = evidenceQueryPort;
        this.evidenceRecorder = evidenceRecorder;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return PROVIDER_ASSERT
     */
    @Override
    public StepType supports() {
        return StepType.PROVIDER_ASSERT;
    }

    /**
     * assertion 목록을 평가해 Provider truth를 판정한다.
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
                    "provider.assert step에 assertions 배열이 필요합니다.");
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
                    .withCorrelation(context.step().stepId(), null, null)
                    .withAttribute("assertKind", "provider"));
            return new StepExecutionResult.Completed(null);
        }
        evidenceRecorder.append(AppendEvidenceCommand
                .of(context.run().id(), EvidenceType.EVIDENCE_ASSERTION_FAILED, EvidenceSource.ASSERTION)
                .withCorrelation(context.step().stepId(), null, null)
                .withAttribute("assertKind", "provider")
                .withPayload(failures.toString(), null, false));
        return new StepExecutionResult.Failed(ProblemCode.EVIDENCE_ASSERTION_FAILED,
                "Provider truth assertion이 기대와 다릅니다.", failures);
    }

    /**
     * Provider assertion 하나를 평가해 실패 사유를 반환한다. 통과 시 null이다.
     */
    private String evaluateOne(String runId, JsonNode assertion) {
        // resource 상태 단언: canonical state가 기대와 일치해야 한다.
        if (assertion.has("resource")) {
            JsonNode resource = assertion.get("resource");
            String type = resource.path("type").asText();
            String id = resource.path("id").asText();
            Optional<ProviderResource> found = providerStatePort.find(runId, type, id);
            if (found.isEmpty()) {
                return "provider resource not found: " + type + "/" + id;
            }
            if (assertion.has("state")) {
                String expected = assertion.get("state").asText();
                String actual = found.get().canonicalState();
                if (!expected.equals(actual)) {
                    return "provider state expected " + expected + " but was " + actual;
                }
            }
            if (assertion.has("version")) {
                long expected = assertion.get("version").asLong();
                long actual = found.get().version();
                if (expected != actual) {
                    return "provider version expected " + expected + " but was " + actual;
                }
            }
            return null;
        }
        // operation commit 횟수 단언.
        if (assertion.has("operation")) {
            String operation = assertion.get("operation").asText();
            EvidenceMatcher matcher = new EvidenceMatcher(
                    EvidenceType.PROVIDER_OPERATION_COMMITTED,
                    Map.of("operation", operation));
            long actual = evidenceQueryPort.count(runId, matcher);
            if (assertion.has("count") && assertion.get("count").isNumber()
                    && assertion.get("count").asLong() != actual) {
                return "commit count expected " + assertion.get("count").asLong()
                        + " but was " + actual;
            }
            if (assertion.has("greaterThanOrEqual")
                    && actual < assertion.get("greaterThanOrEqual").asLong()) {
                return "commit count expected >= " + assertion.get("greaterThanOrEqual").asLong()
                        + " but was " + actual;
            }
            return null;
        }
        return "지원하지 않는 provider assertion 형식입니다.";
    }
}
