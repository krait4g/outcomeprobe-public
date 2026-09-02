package io.outcomeprobe.emulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.DslDurations;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Provider Emulator ingress 요청 처리를 조정하는 애플리케이션 서비스다.
 *
 * <p>책임: alias → 활성 Run 해석, operation 매칭, expectation 선택, idempotency
 * 판단, 전이 검증을 순서대로 수행하고 commit은 {@link EmulatorCommitService}의
 * 트랜잭션에 위임한다.</p>
 * <p>핵심 불변식: Provider commit 여부와 HTTP 응답 성공 여부는 독립적으로
 * 기록·전달된다. 이 서비스는 응답을 보내기 전에 commit 결과를 먼저 확정한다.</p>
 */
@Service
public class EmulatorService {

    private final AliasLockPort aliasLockPort;
    private final ProviderStatePort providerStatePort;
    private final ExpectedInvocationPort expectedInvocationPort;
    private final InvocationReceiptPort invocationReceiptPort;
    private final EvidenceRecorder evidenceRecorder;
    private final EmulatorCommitService commitService;
    private final RequestMatcherEvaluator matcherEvaluator;
    private final ObjectMapper objectMapper;

    /**
     * ingress 조정에 필요한 협업 객체를 주입한다.
     *
     * @param aliasLockPort alias 독점·문맥 포트
     * @param providerStatePort Provider 상태 포트
     * @param expectedInvocationPort expectation 포트
     * @param invocationReceiptPort idempotency 영수증 포트
     * @param evidenceRecorder 증거 기록기
     * @param commitService commit 트랜잭션 서비스
     * @param matcherEvaluator 요청 matcher 평가기
     * @param objectMapper JSON 해석 mapper
     */
    public EmulatorService(AliasLockPort aliasLockPort,
                           ProviderStatePort providerStatePort,
                           ExpectedInvocationPort expectedInvocationPort,
                           InvocationReceiptPort invocationReceiptPort,
                           EvidenceRecorder evidenceRecorder,
                           EmulatorCommitService commitService,
                           RequestMatcherEvaluator matcherEvaluator,
                           ObjectMapper objectMapper) {
        this.aliasLockPort = aliasLockPort;
        this.providerStatePort = providerStatePort;
        this.expectedInvocationPort = expectedInvocationPort;
        this.invocationReceiptPort = invocationReceiptPort;
        this.evidenceRecorder = evidenceRecorder;
        this.commitService = commitService;
        this.matcherEvaluator = matcherEvaluator;
        this.objectMapper = objectMapper;
    }

    /**
     * alias로 들어온 Provider API 요청을 처리해 응답 계획을 반환한다.
     *
     * <p>왜 직접 응답하지 않는가: 의도적 응답 지연은 HTTP 쓰기 직전에 적용해야
     * 하므로, 이 서비스는 계획만 만들고 지연·쓰기는 Controller가 수행한다.</p>
     *
     * @param alias Emulator alias
     * @param method HTTP method
     * @param path alias 이후 상대 경로
     * @param headers 원본 요청 header
     * @param bodyBytes 원본 요청 body
     * @return 응답 계획
     */
    public EmulationOutcome handle(String alias, String method, String path,
                                   Map<String, String> headers, byte[] bodyBytes) {
        Optional<EmulatorRunContext> context = aliasLockPort.activeContext(alias);
        if (context.isEmpty()) {
            // 활성 Run이 없는 alias는 존재하지 않는 Provider처럼 404로 답한다.
            return problem(404, "PROVIDER_ALIAS_NOT_FOUND", "활성 Run이 없는 alias입니다.");
        }
        EmulatorRunContext run = context.get();
        String runId = run.runId();
        ProviderContract contract = run.contract();

        OperationMatch match = matchOperation(contract, method, path);
        if (match == null) {
            appendRejected(runId, null, "PROVIDER_OPERATION_UNEXPECTED",
                    Map.of("method", method, "path", path));
            return problem(409, "PROVIDER_OPERATION_UNEXPECTED",
                    "계약에 정의되지 않은 제공자 호출입니다.");
        }

        String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
        evidenceRecorder.append(AppendEvidenceCommand
                .of(runId, EvidenceType.PROVIDER_REQUEST_RECEIVED, EvidenceSource.EMULATOR)
                .withAttribute("operation", match.operationKey)
                .withAttribute("method", method)
                .withAttribute("path", path));

        // transition이 없는 operation은 조회(getPayment 등)다. 계약 자체가 허용하므로
        // expectation 없이 처리하고 stale view는 자원 상태가 결정한다.
        if (match.operation.transition() == null) {
            ProviderContract.RequestSpec queryRequest = match.operation.request();
            String queryResourceId = extractResourceId(queryRequest, match.pathVariables, headers, bodyText);
            Optional<ProviderResource> queryResource =
                    providerStatePort.find(runId, queryRequest.resourceType(), queryResourceId);
            if (queryResource.isEmpty()) {
                appendRejected(runId, match.operationKey, "PROVIDER_REQUEST_MISMATCH",
                        Map.of("resourceId", queryResourceId));
                return problem(404, "PROVIDER_REQUEST_MISMATCH", "조회할 resource가 없습니다.");
            }
            ProviderOperationRequest queryRequestContext = new ProviderOperationRequest(
                    runId, run.seed(), match.operationKey, match.operation, null, queryResource.get(),
                    queryResourceId, headers, bodyText, match.pathVariables, null,
                    HashValue.sha256(bodyBytes).hexValue());
            Optional<CommitOutcome> queryOutcome = commitService.registerQuery(queryRequestContext);
            if (queryOutcome.isEmpty()) {
                return problem(404, "PROVIDER_REQUEST_MISMATCH", "조회할 resource가 없습니다.");
            }
            return toOutcome(queryOutcome.get(), runId, match.operationKey);
        }

        List<ExpectedInvocation> candidates = expectedInvocationPort.candidates(runId, match.operationKey);
        List<ExpectedInvocation> matched = candidates.stream()
                .filter(candidate -> matcherEvaluator.matches(
                        candidate.matcher(), headers, bodyText, match.pathVariables))
                .toList();
        if (matched.isEmpty()) {
            appendRejected(runId, match.operationKey, "PROVIDER_OPERATION_UNEXPECTED",
                    Map.of("operation", match.operationKey));
            return problem(409, "PROVIDER_OPERATION_UNEXPECTED",
                    "현재 시나리오가 허용하지 않은 제공자 호출입니다.");
        }
        if (matched.size() > 1) {
            appendRejected(runId, match.operationKey, "PROVIDER_EXPECTATION_AMBIGUOUS",
                    Map.of("operation", match.operationKey));
            return problem(409, "PROVIDER_EXPECTATION_AMBIGUOUS",
                    "동일하게 매칭되는 expectation이 둘 이상입니다.");
        }
        ExpectedInvocation expectation = matched.get(0);

        evidenceRecorder.append(AppendEvidenceCommand
                .of(runId, EvidenceType.PROVIDER_OPERATION_MATCHED, EvidenceSource.EMULATOR)
                .withCorrelation(expectation.stepId(), match.operation.request().resourceType(), null)
                .withAttribute("operation", match.operationKey));

        JsonNode behavior = expectation.behavior();
        String faultType = behavior.path("responseFault").path("type").asText("NONE");
        Duration delay = behavior.path("responseFault").path("delay").isTextual()
                ? DslDurations.parse(behavior.path("responseFault").path("delay").asText())
                : Duration.ZERO;

        if ("HTTP_ERROR_BEFORE_COMMIT".equals(faultType)) {
            // commit 없이 장애 응답만 노출해 Target의 실패 경로를 재현한다.
            int errorStatus = behavior.path("responseFault").path("status").asInt(500);
            evidenceRecorder.append(AppendEvidenceCommand
                    .of(runId, EvidenceType.PROVIDER_RESPONSE_FAULT_APPLIED, EvidenceSource.EMULATOR)
                    .withCorrelation(expectation.stepId(), match.operation.request().resourceType(), null)
                    .withAttribute("operation", match.operationKey)
                    .withAttribute("faultType", faultType)
                    .withAttribute("faultOrigin", "SCENARIO_PLANNED"));
            return new EmulationOutcome(errorStatus, Map.of(), new byte[0], Duration.ZERO,
                    "PROVIDER_RESPONSE_FAULT_APPLIED");
        }
        if ("DELAY_BEFORE_COMMIT".equals(faultType)) {
            sleepQuietly(delay);
        }

        ProviderContract.RequestSpec requestSpec = match.operation.request();
        String resourceId = extractResourceId(requestSpec, match.pathVariables, headers, bodyText);
        Optional<ProviderResource> resourceOpt =
                providerStatePort.find(runId, requestSpec.resourceType(), resourceId);
        if (resourceOpt.isEmpty()) {
            appendRejected(runId, match.operationKey, "PROVIDER_REQUEST_MISMATCH",
                    Map.of("resourceId", resourceId));
            return problem(409, "PROVIDER_REQUEST_MISMATCH",
                    "seed되지 않은 resource에 대한 호출입니다: " + resourceId);
        }
        ProviderResource resource = resourceOpt.get();

        String requestHash = HashValue.sha256(bodyBytes).hexValue();

        // idempotency 위반과 replay는 commit 전에 결정해 Provider truth 중복 변경을 막는다.
        IdempotencyDecision decision = decideIdempotency(requestSpec, headers, bodyText, runId,
                match.operationKey, requestHash);
        if (decision.rejectOutcome() != null) {
            return decision.rejectOutcome();
        }

        if (decision.replaySnapshot() != null) {
            ProviderOperationRequest replayRequest = new ProviderOperationRequest(
                    runId, run.seed(), match.operationKey, match.operation, expectation, resource,
                    resourceId, headers, bodyText, match.pathVariables, decision.keyHash(), requestHash);
            CommitOutcome replay = commitService.replayReceipt(replayRequest, decision.replaySnapshot());
            return toOutcome(replay, runId, match.operationKey);
        }

        if (match.operation.transition() == null) {
            // transition이 없는 operation은 상태 조회(getPayment 등)로 취급한다.
            ProviderOperationRequest queryRequest = new ProviderOperationRequest(
                    runId, run.seed(), match.operationKey, match.operation, expectation, resource,
                    resourceId, headers, bodyText, match.pathVariables, decision.keyHash(), requestHash);
            Optional<CommitOutcome> queryOutcome = commitService.registerQuery(queryRequest);
            if (queryOutcome.isEmpty()) {
                return problem(404, "PROVIDER_REQUEST_MISMATCH", "조회할 resource가 없습니다.");
            }
            return toOutcome(queryOutcome.get(), runId, match.operationKey);
        }

        if (!match.operation.transition().from().contains(resource.canonicalState())) {
            appendRejected(runId, match.operationKey, "PROVIDER_STATE_TRANSITION_INVALID",
                    Map.of("currentState", resource.canonicalState(),
                            "operation", match.operationKey));
            return problem(409, "PROVIDER_STATE_TRANSITION_INVALID",
                    "현재 상태에서 허용되지 않은 operation입니다.");
        }

        ProviderOperationRequest commitRequest = new ProviderOperationRequest(
                runId, run.seed(), match.operationKey, match.operation, expectation, resource,
                resourceId, headers, bodyText, match.pathVariables, decision.keyHash(), requestHash);
        CommitOutcome outcome = commitService.commitOperation(commitRequest);
        if (outcome.conflict()) {
            // CAS 충돌은 동시 전의를 의미한다. 한 번만 재시도하고 실패하면 충돌을 숨기지 않는다.
            Optional<ProviderResource> reloaded =
                    providerStatePort.find(runId, requestSpec.resourceType(), resourceId);
            Optional<ExpectedInvocation> refreshed =
                    expectedInvocationPort.firstByOperation(runId, match.operationKey);
            if (reloaded.isPresent() && refreshed.isPresent()) {
                ProviderOperationRequest retry = new ProviderOperationRequest(
                        runId, run.seed(), match.operationKey, match.operation, refreshed.get(),
                        reloaded.get(), resourceId, headers, bodyText, match.pathVariables,
                        decision.keyHash(), requestHash);
                outcome = commitService.commitOperation(retry);
            }
            if (outcome.conflict()) {
                return problem(409, "DATABASE_CONFLICT", "동시 상태 전이 충돌이 발생했습니다.");
            }
        }
        return toOutcome(outcome, runId, match.operationKey);
    }

    /**
     * commit 결과를 HTTP 응답 계획(상태·헤더·지연)으로 변환한다.
     */
    private EmulationOutcome toOutcome(CommitOutcome outcome, String runId, String operationKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-OutcomeProbe-Run-Id", runId);
        headers.put("X-OutcomeProbe-Operation", operationKey);
        headers.put("Content-Type", "application/json");
        return new EmulationOutcome(outcome.status(), headers, outcome.body(),
                outcome.responseDelay(), null);
    }

    /**
     * 거부 응답(problem+json) 계획을 생성한다.
     */
    private EmulationOutcome problem(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        Map<String, String> headers = Map.of("Content-Type", "application/problem+json");
        try {
            return new EmulationOutcome(status, headers,
                    objectMapper.writeValueAsBytes(body), Duration.ZERO, code);
        } catch (tools.jackson.core.JacksonException e) {
            return new EmulationOutcome(status, headers, new byte[0], Duration.ZERO, code);
        }
    }

    /**
     * 예상치 못한 Provider 호출 거부 사실을 Evidence로 기록한다.
     */
    private void appendRejected(String runId, String operationKey, String code,
                                Map<String, String> attributes) {
        AppendEvidenceCommand command = AppendEvidenceCommand
                .of(runId, EvidenceType.PROVIDER_REQUEST_REJECTED, EvidenceSource.EMULATOR)
                .withAttribute("code", code);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            command = command.withAttribute(entry.getKey(), entry.getValue());
        }
        evidenceRecorder.append(command);
    }

    /**
     * 응답 지연 fault를 재현하기 위해 중단 없이 대기한다. 인터럽트는 표시만 남긴다.
     */
    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 요청 method·경로를 Contract operation에 매칭하고 path 변수를 추출한다.
     */
    private OperationMatch matchOperation(ProviderContract contract, String method, String path) {
        for (Map.Entry<String, ProviderContract.OperationSpec> entry : contract.operations().entrySet()) {
            ProviderContract.OperationSpec operation = entry.getValue();
            if (!operation.request().method().equalsIgnoreCase(method)) {
                continue;
            }
            Optional<Map<String, String>> variables =
                    PathTemplate.compile(operation.request().path()).match(path);
            if (variables.isPresent()) {
                return new OperationMatch(entry.getKey(), operation, variables.get());
            }
        }
        return null;
    }

    /**
     * Contract의 resourceId 추출 규칙(PATH/HEADER/BODY)으로 자원 ID를 뽑는다.
     */
    private String extractResourceId(ProviderContract.RequestSpec requestSpec,
                                     Map<String, String> pathVariables,
                                     Map<String, String> headers, String bodyText) {
        ProviderContract.ResourceRefSpec ref = requestSpec.resourceId();
        if (ref == null) {
            return "";
        }
        return switch (ref.source().toUpperCase(Locale.ROOT)) {
            case "PATH" -> pathVariables.getOrDefault(ref.expression(), "");
            case "HEADER" -> headers.getOrDefault(ref.expression(), "");
            case "BODY" -> String.valueOf(readJsonPath(bodyText, ref.expression()));
            default -> "";
        };
    }

    /**
     * body에서 JSONPath 값을 읽는다. 경로 없음은 null로 취급한다.
     */
    private Object readJsonPath(String body, String jsonPath) {
        try {
            return JsonPath.read(body, jsonPath);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * idempotency key 재사용·충돌을 판정한다.
     * 같은 key·같은 요청은 replay, 다른 요청은 409 충돌로 처리한다.
     */
    private IdempotencyDecision decideIdempotency(ProviderContract.RequestSpec requestSpec,
                                                  Map<String, String> headers, String bodyText,
                                                  String runId, String operationKey,
                                                  String requestHash) {
        ProviderContract.IdempotencyKeySpec idemSpec = requestSpec.idempotencyKey();
        if (idemSpec == null) {
            return IdempotencyDecision.none();
        }
        String rawKey = switch (idemSpec.source().toUpperCase(Locale.ROOT)) {
            case "HEADER" -> headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(idemSpec.expression()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            case "BODY" -> {
                Object value = readJsonPath(bodyText, idemSpec.expression());
                yield value == null ? null : String.valueOf(value);
            }
            default -> null;
        };
        if (rawKey == null) {
            if (idemSpec.isRequired()) {
                return IdempotencyDecision.reject(problem(400, "PROVIDER_REQUEST_MISMATCH",
                        "필수 idempotency key가 없습니다."));
            }
            return IdempotencyDecision.none();
        }
        String keyHash = HashValue.sha256(rawKey).hexValue();
        Optional<String> storedHash = invocationReceiptPort.findRequestHash(runId, operationKey, keyHash);
        if (storedHash.isPresent()) {
            if (!storedHash.get().equals(requestHash)) {
                // 같은 key에 다른 요청은 조용히 덮어쓰지 않고 명시적 conflict로 보고한다.
                appendRejected(runId, operationKey, "PROVIDER_IDEMPOTENCY_KEY_REUSED",
                        Map.of("idempotencyKeyHash", keyHash.substring(0, 12)));
                return IdempotencyDecision.reject(problem(409, "PROVIDER_IDEMPOTENCY_KEY_REUSED",
                        "동일 idempotency key로 다른 요청이 접수됐습니다."));
            }
            JsonNode snapshot = invocationReceiptPort.findResult(runId, operationKey, keyHash)
                    .orElse(null);
            return IdempotencyDecision.replay(keyHash, snapshot);
        }
        return IdempotencyDecision.fresh(keyHash);
    }

    /**
     * operation 매칭 결과를 묶는 내부 값이다.
     *
     * @param operationKey 매칭된 operation key
     * @param operation 매칭된 operation 계약
     * @param pathVariables 추출된 path 변수
     */
    private record OperationMatch(String operationKey, ProviderContract.OperationSpec operation,
                                  Map<String, String> pathVariables) {
    }

    /**
     * idempotency 판단 결과다.
     *
     * @param keyHash 멱등 키 해시, 해당 없으면 null
     * @param replaySnapshot replay할 최초 결과, 해당 없으면 null
     * @param rejectOutcome 즉시 거부 응답, 해당 없으면 null
     */
    private record IdempotencyDecision(String keyHash, JsonNode replaySnapshot,
                                       EmulationOutcome rejectOutcome) {

        /**
         * idempotency 계약이 없는 요청의 결과를 만든다.
         *
         * @return 판단 없음
         */
        static IdempotencyDecision none() {
            return new IdempotencyDecision(null, null, null);
        }

        /**
         * 처음 접수된 key의 결과를 만든다.
         *
         * @param keyHash 멱등 키 해시
         * @return 신규 처리 판단
         */
        static IdempotencyDecision fresh(String keyHash) {
            return new IdempotencyDecision(keyHash, null, null);
        }

        /**
         * 최초 결과를 재사용하는 replay 판단을 만든다.
         *
         * @param keyHash 멱등 키 해시
         * @param snapshot 최초 응답 계획
         * @return replay 판단
         */
        static IdempotencyDecision replay(String keyHash, JsonNode snapshot) {
            return new IdempotencyDecision(keyHash, snapshot, null);
        }

        /**
         * 즉시 거부 판단을 만든다.
         *
         * @param outcome 거부 응답 계획
         * @return 거부 판단
         */
        static IdempotencyDecision reject(EmulationOutcome outcome) {
            return new IdempotencyDecision(null, null, outcome);
        }
    }
}
