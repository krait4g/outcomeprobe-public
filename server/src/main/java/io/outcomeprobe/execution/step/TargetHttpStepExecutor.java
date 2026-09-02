package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.execution.TargetHttpPort;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.probe.AssertionEvaluator;
import io.outcomeprobe.probe.AssertionOperator;
import io.outcomeprobe.probe.AssertionSpec;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.SecretResolutionException;
import io.outcomeprobe.profile.TargetHeaderResolutionException;
import io.outcomeprobe.profile.TargetHeaderResolver;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * target.http Step을 실행해 Target에 trigger·초기화 요청을 보낸다.
 *
 * <p>책임: Profile allowlist 안에서 요청을 보내고, expect(status/statusIn)와
 * 응답 assertion을 검사한다. 연결 실패는 deadline 안에서 재시도한다.</p>
 * <p>핵심 불변식: HTTP 2xx는 업무 성공 증거가 아니다. 이 Step의 통과는 요청
 * 계약 충족일 뿐이며, 최종 수렴 판정은 probe.eventually가 담당한다.</p>
 */
@Component
public class TargetHttpStepExecutor implements StepExecutor {

    private final TargetHttpPort targetHttpPort;
    private final TargetHeaderResolver targetHeaderResolver;
    private final EvidenceRecorder evidenceRecorder;
    private final AssertionEvaluator assertionEvaluator;
    private final ClockProvider clockProvider;
    private final ObjectMapper objectMapper;

    /**
     * Target 요청 실행에 필요한 협업 객체를 주입한다.
     *
     * @param targetHttpPort Target HTTP 포트
     * @param targetHeaderResolver Profile·Step·secret header 병합기
     * @param evidenceRecorder 증거 기록기
     * @param assertionEvaluator assertion 평가기
     * @param clockProvider 대기 시각 계산용 clock
     * @param objectMapper JSON 변환 mapper
     */
    public TargetHttpStepExecutor(TargetHttpPort targetHttpPort,
                                  TargetHeaderResolver targetHeaderResolver,
                                  EvidenceRecorder evidenceRecorder, AssertionEvaluator assertionEvaluator,
                                  ClockProvider clockProvider, ObjectMapper objectMapper) {
        this.targetHttpPort = targetHttpPort;
        this.targetHeaderResolver = targetHeaderResolver;
        this.evidenceRecorder = evidenceRecorder;
        this.assertionEvaluator = assertionEvaluator;
        this.clockProvider = clockProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return TARGET_HTTP
     */
    @Override
    public StepType supports() {
        return StepType.TARGET_HTTP;
    }

    /**
     * 요청을 전송하고 expect 계약을 검사한다.
     *
     * <p>모호성 처리: 연결 실패는 Step deadline까지 재시도하지만, 응답을 받은 뒤
     * expect 불일치는 재시도하지 않는다. 응답 수신 여부가 명확하면 결과를 숨기지 않는다.</p>
     *
     * @param context 실행 문맥
     * @return 완료·대기·실패·오류 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        JsonNode detail = context.step().inputSnapshot();
        JsonNode request = detail.get("request");
        if (request == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "target.http step에 request가 없습니다.");
        }
        String method = request.path("method").asText("GET");
        String path = request.path("path").asText(null);
        if (path == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "request.path가 필요합니다.");
        }

        TargetProfileSafeSnapshot profileSnapshot = context.plan().targetProfileSnapshot();
        if (profileSnapshot == null || profileSnapshot.profile() == null) {
            return new StepExecutionResult.Errored(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED,
                    "Run에 Target Profile snapshot이 없습니다.");
        }
        TargetProfile profile = profileSnapshot.profile();
        String evidencePath = pathOnly(path);
        if (!profile.isPathAllowed(path)) {
            return new StepExecutionResult.Errored(ProblemCode.TARGET_PATH_NOT_ALLOWED,
                    "Profile allowlist에 없는 경로입니다: " + evidencePath);
        }

        Map<String, String> explicitHeaders = new LinkedHashMap<>();
        JsonNode headersNode = request.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            headersNode.properties().forEach(entry ->
                    explicitHeaders.put(entry.getKey(), entry.getValue().asText()));
        }
        String bodyText = request.has("body") ? request.get("body").toString() : null;
        if (bodyText != null && explicitHeaders.keySet().stream()
                .noneMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
            // body가 있는 요청은 JSON으로 전송한다. Target MVC가 415로 거부하지 않게 한다.
            explicitHeaders.put("Content-Type", "application/json");
        }

        Map<String, String> headers;
        try {
            headers = targetHeaderResolver.resolve(profile, explicitHeaders);
        } catch (SecretResolutionException exception) {
            return new StepExecutionResult.Errored(ProblemCode.TARGET_SECRET_UNRESOLVED,
                    exception.getMessage());
        } catch (TargetHeaderResolutionException exception) {
            return new StepExecutionResult.Errored(exception.problemCode(), exception.getMessage());
        }

        evidenceRecorder.append(AppendEvidenceCommand
                .of(context.run().id(), EvidenceType.TARGET_REQUEST_SENT, EvidenceSource.ENGINE)
                .withCorrelation(context.step().stepId(), null, null)
                .withAttribute("method", method)
                .withAttribute("path", evidencePath)
                .withAttribute("pathQueryRedacted", String.valueOf(!evidencePath.equals(path))));

        TargetHttpPort.TargetHttpResponse response =
                targetHttpPort.call(profile, method, path, headers, bodyText);

        if (!response.reached()) {
            evidenceRecorder.append(AppendEvidenceCommand
                    .of(context.run().id(), EvidenceType.TARGET_REQUEST_FAILED, EvidenceSource.ENGINE)
                    .withCorrelation(context.step().stepId(), null, null)
                    .withAttribute("failureCode", String.valueOf(response.failureCode()))
                    .withAttribute("faultOrigin", "OUTCOMEPROBE_INTERNAL"));
            if (!isRetryableConnectionFailure(response.failureCode())) {
                return new StepExecutionResult.Errored(
                        permanentTargetProblemCode(response.failureCode()),
                        "Target 요청이 영구 안전·계약 오류로 차단됐습니다: "
                                + response.failureCode());
            }
            // 연결 실패만 deadline 안에서 재시도해 일시적 기동 지연을 실패로 고정하지 않는다.
            if (context.step().deadlineAt() != null
                    && clockProvider.now().isAfter(context.step().deadlineAt())) {
                return new StepExecutionResult.Errored(ProblemCode.TARGET_CONNECTION_FAILED,
                        "Target에 연결할 수 없습니다: " + response.failureCode());
            }
            return new StepExecutionResult.Waiting(clockProvider.now().plusMillis(300), null);
        }

        String bodyHash = response.bodyText() == null
                ? null
                : HashValue.sha256(response.bodyText().getBytes(StandardCharsets.UTF_8)).hexValue();
        evidenceRecorder.append(AppendEvidenceCommand
                .of(context.run().id(), EvidenceType.TARGET_RESPONSE_RECEIVED, EvidenceSource.ENGINE)
                .withCorrelation(context.step().stepId(), null, null)
                .withAttribute("status", String.valueOf(response.statusCode()))
                .withPayload(null, bodyHash, false));

        if (!statusAccepted(detail.get("expect"), response.statusCode())) {
            return new StepExecutionResult.Failed(ProblemCode.TARGET_RESPONSE_UNEXPECTED,
                    "Target 응답 상태가 expect와 다릅니다: " + response.statusCode(), null);
        }

        List<AssertionSpec> assertions = parseAssertions(detail.path("expect").get("assertions"));
        if (!assertions.isEmpty()) {
            AssertionEvaluator.AssertionResult result =
                    assertionEvaluator.evaluate(response.body(), assertions);
            if (!result.passed()) {
                return new StepExecutionResult.Failed(ProblemCode.TARGET_RESPONSE_UNEXPECTED,
                        "Target 응답 assertion이 실패했습니다.", null);
            }
        }

        ObjectNode output = objectMapper.createObjectNode();
        output.put("status", response.statusCode());
        if (bodyHash != null) {
            output.put("bodyHash", bodyHash);
        }
        return new StepExecutionResult.Completed(output);
    }

    /**
     * expect(status 또는 statusIn)와 실제 상태 코드를 대조한다.
     */
    private boolean statusAccepted(JsonNode expect, int statusCode) {
        if (expect == null || expect.isNull()) {
            return statusCode >= 200 && statusCode < 300;
        }
        if (expect.has("status")) {
            return expect.get("status").asInt() == statusCode;
        }
        if (expect.has("statusIn")) {
            for (JsonNode candidate : expect.get("statusIn")) {
                if (candidate.asInt() == statusCode) {
                    return true;
                }
            }
            return false;
        }
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * expect.assertions 노드를 AssertionSpec 목록으로 변환한다.
     */
    private List<AssertionSpec> parseAssertions(JsonNode assertionsNode) {
        List<AssertionSpec> result = new ArrayList<>();
        if (assertionsNode == null || !assertionsNode.isArray()) {
            return result;
        }
        for (JsonNode node : assertionsNode) {
            result.add(JsonAssertions.parseSpec(node));
        }
        return result;
    }

    /**
     * deadline 안에서 다시 시도할 수 있는 연결 계층 실패인지 판정한다.
     *
     * @param failureCode Target adapter의 고정 실패 코드
     * @return 일반 연결 실패면 true, interrupt를 포함한 나머지는 false
     */
    private boolean isRetryableConnectionFailure(String failureCode) {
        return "TARGET_CONNECTION_FAILED".equals(failureCode);
    }

    /**
     * 재시도할 수 없는 adapter 실패 코드를 공개 ProblemCode로 변환한다.
     *
     * @param failureCode Target adapter의 영구 실패 코드
     * @return Step과 Run에 보고할 문제 코드
     */
    private ProblemCode permanentTargetProblemCode(String failureCode) {
        if (failureCode == null) {
            return ProblemCode.TARGET_RESPONSE_UNEXPECTED;
        }
        return switch (failureCode) {
            case "TARGET_PATH_NOT_ALLOWED" -> ProblemCode.TARGET_PATH_NOT_ALLOWED;
            case "TARGET_METHOD_NOT_ALLOWED" -> ProblemCode.TARGET_METHOD_NOT_ALLOWED;
            case "TARGET_URI_INVALID" -> ProblemCode.TARGET_URI_INVALID;
            case "TARGET_HOST_NOT_ALLOWED" -> ProblemCode.TARGET_HOST_NOT_ALLOWED;
            case "TARGET_HEADER_INVALID" -> ProblemCode.TARGET_HEADER_INVALID;
            case "TARGET_TLS_INVALID" -> ProblemCode.TARGET_TLS_INVALID;
            case "TARGET_RESPONSE_TOO_LARGE" -> ProblemCode.TARGET_RESPONSE_TOO_LARGE;
            case "TARGET_REDIRECT_REJECTED" -> ProblemCode.TARGET_RESPONSE_UNEXPECTED;
            case "INTERRUPTED" -> ProblemCode.TARGET_CONNECTION_FAILED;
            default -> ProblemCode.TARGET_RESPONSE_UNEXPECTED;
        };
    }

    /**
     * Timeline과 Report에는 query를 제외한 경로만 남긴다.
     *
     * <p>Target 호출 자체에는 원래 query가 필요하지만 식별자나 임시 credential이 query에 들어간
     * 사용자 서비스에서 이를 Evidence로 복제해서는 안 된다. URI 재해석 없이 첫 물음표 이전만
     * 취해 percent-encoding이나 잘못된 query가 redaction을 우회하지 못하게 한다.</p>
     *
     * @param path Target Profile에 대조하고 전송할 원래 상대 경로
     * @return query가 제거된 Evidence 전용 경로
     */
    private String pathOnly(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }
}
