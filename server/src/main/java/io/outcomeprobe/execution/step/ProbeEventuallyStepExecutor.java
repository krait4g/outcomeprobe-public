package io.outcomeprobe.execution.step;

import com.jayway.jsonpath.JsonPath;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.ProbeRunFenceFactory;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.PostgresqlTimestamp;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.infrastructure.metrics.MetricsService;
import io.outcomeprobe.probe.AssertionEvaluator;
import io.outcomeprobe.probe.AssertionSpec;
import io.outcomeprobe.probe.ProbeAssertionRedactor;
import io.outcomeprobe.probe.ProbeAttempt;
import io.outcomeprobe.probe.ProbeAttemptEvidenceFactory;
import io.outcomeprobe.probe.ProbeAttemptIntent;
import io.outcomeprobe.probe.ProbeAttemptStart;
import io.outcomeprobe.probe.ProbeAttemptStartState;
import io.outcomeprobe.probe.ProbeAttemptTransactionCoordinator;
import io.outcomeprobe.probe.ProbeDecision;
import io.outcomeprobe.probe.ProbeDecisionPolicy;
import io.outcomeprobe.probe.ProbeObservation;
import io.outcomeprobe.probe.ProbeRunFence;
import io.outcomeprobe.probe.StateProbePort;
import io.outcomeprobe.profile.SecretResolutionException;
import io.outcomeprobe.profile.TargetHeaderResolutionException;
import io.outcomeprobe.profile.TargetHeaderResolver;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.scenario.definition.DslDurations;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * probe.eventually Step을 실행해 Target 최종 상태가 assertion을 만족하는지 반복 조회한다.
 *
 * <p>책임: 요청 intent를 짧은 트랜잭션으로 먼저 고정하고 외부 HTTP를 한 번 수행한 뒤,
 * 제한 시간·일시 오류·연속 성공 판정과 완료 Evidence를 원자 기록한다.</p>
 * <p>핵심 불변식: 응답 body 원문은 메모리 assertion 평가 뒤 버리고, DB·Evidence·
 * 문제 상세에는 raw-byte hash와 redaction된 assertion 결과만 전달한다.</p>
 */
@Component
public class ProbeEventuallyStepExecutor implements StepExecutor {

    private static final int MAX_PROBE_ATTEMPTS = 2_000;
    private static final int MAX_ASSERTIONS = 32;
    private static final int MAX_JSON_PATH_BYTES = 1_024;

    private final StateProbePort stateProbePort;
    private final TargetHeaderResolver targetHeaderResolver;
    private final ProbeAttemptTransactionCoordinator attemptCoordinator;
    private final AssertionEvaluator assertionEvaluator;
    private final ProbeDecisionPolicy decisionPolicy;
    private final ClockProvider clockProvider;
    private final MetricsService metricsService;

    /**
     * intent 선기록과 수렴 판정·완료 저장에 필요한 협업 객체를 주입한다.
     *
     * @param stateProbePort 상태 조회 HTTP 포트
     * @param targetHeaderResolver Profile·Step·secret header 병합기
     * @param attemptCoordinator attempt와 Evidence 원자 저장 경계
     * @param assertionEvaluator assertion 평가기
     * @param clockProvider deadline과 다음 실행 시각 제공자
     * @param metricsService 운영 메트릭 기록기
     */
    public ProbeEventuallyStepExecutor(StateProbePort stateProbePort,
                                       TargetHeaderResolver targetHeaderResolver,
                                       ProbeAttemptTransactionCoordinator attemptCoordinator,
                                       AssertionEvaluator assertionEvaluator,
                                       ClockProvider clockProvider,
                                       MetricsService metricsService) {
        this.stateProbePort = stateProbePort;
        this.targetHeaderResolver = targetHeaderResolver;
        this.attemptCoordinator = attemptCoordinator;
        this.assertionEvaluator = assertionEvaluator;
        this.decisionPolicy = new ProbeDecisionPolicy();
        this.clockProvider = clockProvider;
        this.metricsService = metricsService;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return PROBE_EVENTUALLY
     */
    @Override
    public StepType supports() {
        return StepType.PROBE_EVENTUALLY;
    }

    /**
     * Target을 한 번 관측하고 영구 오류·재시도·수렴·deadline 결과를 반환한다.
     *
     * <p>모든 구성·header 검증 뒤 coordinator가 요청 intent를 확정해야만 HTTP를 호출한다.
     * 기존 결과 미확정 intent는 재전송하지 않고 중단 상태로 복구하며, 완료 관측은
     * Run의 Step 상태 전이와 같은 트랜잭션에서 저장한다.</p>
     *
     * @param context 실행 문맥
     * @return 완료·대기·실패·오류 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        JsonNode detail = context.step().inputSnapshot();
        ProbeConfiguration configuration;
        try {
            configuration = configuration(detail, context.plan().timeoutMs());
        } catch (RuntimeException exception) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    exception.getMessage());
        }
        if (context.step().attemptCount() >= MAX_PROBE_ATTEMPTS) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "probe.eventually attempt는 2,000회를 넘을 수 없습니다.");
        }
        Duration within = configuration.within();
        Duration interval = configuration.interval();
        int requiredConsecutive = configuration.requiredConsecutive();
        Instant invocationStartedAt = clockProvider.now();
        Instant started = probeStartedAt(context.step().lastObservation(),
                context.step().waitStartedAt(), invocationStartedAt);

        TargetProfileSafeSnapshot profileSnapshot = context.plan().targetProfileSnapshot();
        if (profileSnapshot == null || profileSnapshot.profile() == null) {
            return new StepExecutionResult.Errored(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED,
                    "Run에 Target Profile snapshot이 없습니다.");
        }
        TargetProfile profile = profileSnapshot.profile();
        String method = detail.path("request").path("method").asText("GET");
        if (!"GET".equalsIgnoreCase(method)) {
            return new StepExecutionResult.Errored(ProblemCode.TARGET_METHOD_NOT_ALLOWED,
                    "probe.eventually request.method는 GET만 허용됩니다.");
        }
        method = "GET";
        String path = detail.path("request").path("path").asText(null);
        if (path == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "probe.eventually step에 request.path가 필요합니다.");
        }

        List<AssertionSpec> assertions;
        try {
            assertions = assertions(detail.get("assertions"));
        } catch (RuntimeException exception) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "probe.eventually assertion이 올바르지 않습니다.");
        }
        if (assertions.isEmpty()) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "probe.eventually step에는 하나 이상의 assertion이 필요합니다.");
        }
        Set<Integer> retryStatuses;
        try {
            retryStatuses = retryStatuses(detail.get("retryOnStatus"));
        } catch (IllegalArgumentException exception) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    exception.getMessage());
        }

        Map<String, String> explicitHeaders = new LinkedHashMap<>();
        JsonNode headersNode = detail.path("request").get("headers");
        if (headersNode != null && headersNode.isObject()) {
            headersNode.properties().forEach(entry ->
                    explicitHeaders.put(entry.getKey(), entry.getValue().asText()));
        }

        Instant effectiveDeadline = decisionPolicy.effectiveDeadline(
                started, within, context.step().deadlineAt());
        Instant requestedAt = PostgresqlTimestamp.normalize(clockProvider.now());
        int attemptNumber = context.step().attemptCount() + 1;
        ProbeAttemptIntent candidate = new ProbeAttemptIntent(null, context.run().id(),
                context.step().stepId(), attemptNumber, requestedAt);
        if (!requestedAt.isBefore(effectiveDeadline)) {
            return recoverExpiredAttempt(context.run(), candidate, path,
                    context.step().attemptCount());
        }

        ProbeAttemptStart existing = attemptCoordinator.find(candidate).orElse(null);
        if (existing != null) {
            return recoverExistingAttempt(context.run(), existing, path, interval, started, within,
                    context.step().deadlineAt());
        }

        Map<String, String> headers;
        try {
            headers = targetHeaderResolver.resolve(profile, explicitHeaders);
        } catch (SecretResolutionException exception) {
            return new StepExecutionResult.Errored(ProblemCode.TARGET_SECRET_UNRESOLVED,
                    exception.getMessage());
        } catch (TargetHeaderResolutionException exception) {
            return new StepExecutionResult.Errored(exception.problemCode(),
                    exception.getMessage());
        }

        ProbeRunFence beginFence = ProbeRunFenceFactory.at(context.run(), clockProvider.now(),
                ProbeRunFence.Boundary.BEGIN);
        ProbeAttemptStart start = attemptCoordinator.begin(beginFence, candidate,
                ProbeAttemptEvidenceFactory.attempted(candidate, path));

        if (start.state() != ProbeAttemptStartState.CREATED) {
            return recoverExistingAttempt(context.run(), start, path, interval, started, within,
                    context.step().deadlineAt());
        }

        Duration requestBudget = Duration.between(clockProvider.now(), effectiveDeadline);
        if (requestBudget.isZero() || requestBudget.isNegative()) {
            return interruptInFlight(context.run(), start.intent(), path, interval,
                    started, effectiveDeadline);
        }
        ProbeObservation observation = stateProbePort.probe(
                profile, method, path, headers, requestBudget);
        return evaluateAndPersist(context, profile, path, assertions, interval,
                configuration.stabilityInterval(), requiredConsecutive, started,
                effectiveDeadline, retryStatuses, start.intent(), observation);
    }

    /**
     * HTTP 관측을 assertion 평가·redaction·durable 기록 뒤 Step 결과로 변환한다.
     *
     * @param context 실행 문맥
     * @param profile 고정 Target Profile snapshot
     * @param path 요청 상대 경로
     * @param assertions 평가할 assertion 목록
     * @param interval 일반 재시도 간격
     * @param stabilityInterval 연속 성공 확인 간격
     * @param requiredConsecutive 필요한 연속 성공 수
     * @param probeStartedAt 첫 HTTP 전에 고정한 Probe 시작 시각
     * @param effectiveDeadline Run·Step 제한 중 더 이른 실제 deadline
     * @param retryStatuses 허용된 일시 HTTP 상태 부분집합
     * @param intent DB가 확정한 요청 의도
     * @param observation HTTP 또는 요청 전 차단 관측
     * @return 기록된 판정에 대응하는 Step 결과
     */
    private StepExecutionResult evaluateAndPersist(
            StepExecutionContext context,
            TargetProfile profile,
            String path,
            List<AssertionSpec> assertions,
            Duration interval,
            Duration stabilityInterval,
            int requiredConsecutive,
            Instant probeStartedAt,
            Instant effectiveDeadline,
            Set<Integer> retryStatuses,
            ProbeAttemptIntent intent,
            ProbeObservation observation) {
        ProbeObservation authoritativeObservation = authoritativeObservation(intent, observation);
        AssertionEvaluator.AssertionResult evaluation = authoritativeObservation.body() == null
                ? new AssertionEvaluator.AssertionResult(false, List.of())
                : assertionEvaluator.evaluate(authoritativeObservation.body(), assertions);
        boolean assertionsPassed = authoritativeObservation.isValid() && evaluation.passed();
        int previousConsecutive = readConsecutive(context.step().lastObservation());
        int consecutive = assertionsPassed ? previousConsecutive + 1 : 0;
        boolean stabilitySatisfied = assertionsPassed && consecutive >= requiredConsecutive;
        boolean deadlineReached = !authoritativeObservation.respondedAt()
                .isBefore(effectiveDeadline);

        ProbeDecision decision = decisionPolicy.decide(
                authoritativeObservation, assertionsPassed, stabilitySatisfied,
                deadlineReached, retryStatuses);
        ProbeAssertionRedactor.RedactionResult redaction = ProbeAssertionRedactor.redact(
                authoritativeObservation.body(), assertions, evaluation,
                profile.redactionJsonPaths(), assertionEvaluator);
        ProbeAttempt attempt = new ProbeAttempt(intent.id(), intent.runId(), intent.stepId(),
                intent.attemptNumber(), intent.requestedAt(), authoritativeObservation.respondedAt(),
                authoritativeObservation.statusCode(), authoritativeObservation.responseHash(),
                redaction.outcomes(), decision,
                decisionPolicy.failureCode(authoritativeObservation, assertionsPassed));

        StepExecutionResult outcome;
        if (decision.isPermanentError()) {
            outcome = new StepExecutionResult.Errored(
                    decisionPolicy.permanentProblemCode(authoritativeObservation),
                    "Probe 요청 또는 응답을 안전하게 처리할 수 없습니다: "
                            + attempt.failureCode());
        } else if (decision.isDeadlineExceeded()) {
            outcome = new StepExecutionResult.Failed(ProblemCode.PROBE_DEADLINE_EXCEEDED,
                    "제한 시간 안에 최종 상태가 수렴하지 않았습니다.",
                    ProbeAttemptEvidenceFactory.safeDetail(attempt));
        } else if (decision.isConverged()) {
            outcome = new StepExecutionResult.Completed(completedOutput(attempt, consecutive));
        } else {
            Duration nextInterval = assertionsPassed ? stabilityInterval : interval;
            Instant nextActionAt = clockProvider.now().plus(nextInterval);
            if (nextActionAt.isAfter(effectiveDeadline)) {
                nextActionAt = effectiveDeadline;
            }
            outcome = new StepExecutionResult.Waiting(
                    nextActionAt,
                    consecutiveObservation(consecutive, intent.attemptNumber(), decision,
                            probeStartedAt));
        }

        List<AppendEvidenceCommand> commands = evidenceCommands(
                attempt, path, redaction.applied());
        // DB 작업만 지연해 RunEngine의 Step·Run CAS 트랜잭션에 참여시킨다.
        return new StepExecutionResult.Deferred(
                outcome,
                () -> attemptCoordinator.complete(
                        ProbeRunFenceFactory.completion(
                                context.run(), clockProvider.now(), attempt.respondedAt()),
                        attempt, commands),
                () -> metricsService.probeAttempt(decision.name()));
    }

    /**
     * attempt 완료와 함께 원자 저장할 표준 Probe Evidence 명령 목록을 만든다.
     *
     * @param attempt redaction과 판정이 끝난 attempt
     * @param path 요청 상대 경로
     * @param redactionApplied redaction 적용 여부
     * @return 응답과 assertion 상태 Evidence
     */
    private List<AppendEvidenceCommand> evidenceCommands(
            ProbeAttempt attempt, String path, boolean redactionApplied) {
        List<AppendEvidenceCommand> commands = new ArrayList<>();
        commands.add(ProbeAttemptEvidenceFactory.responseReceived(
                attempt, path, redactionApplied));
        EvidenceType assertionType = switch (attempt.decision()) {
            case CONVERGED -> EvidenceType.PROBE_ASSERTION_PASSED;
            case DEADLINE_EXCEEDED -> EvidenceType.PROBE_ASSERTION_FAILED;
            case PENDING, TARGET_UNREACHABLE -> EvidenceType.PROBE_ASSERTION_PENDING;
            case INVALID_RESPONSE, SECURITY_REJECTED -> null;
        };
        if (assertionType != null) {
            commands.add(AppendEvidenceCommand
                    .of(attempt.runId(), assertionType, EvidenceSource.PROBE)
                    .withCorrelation(attempt.stepId(), null, null)
                    .withAttribute("attemptNumber", String.valueOf(attempt.attemptNumber()))
                    .withObservedAt(attempt.respondedAt()));
        }
        return List.copyOf(commands);
    }

    /**
     * 수렴 성공 결과를 원문 없이 report용 JSON으로 만든다.
     *
     * @param attempt 최종 성공 attempt
     * @param consecutive 달성한 연속 성공 횟수
     * @return redaction된 assertion 근거를 포함한 출력
     */
    private JsonNode completedOutput(ProbeAttempt attempt, int consecutive) {
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("status", attempt.statusCode());
        output.put("observedAt", attempt.respondedAt().toString());
        output.put("attempts", attempt.attemptNumber());
        output.put("consecutiveSuccesses", consecutive);
        JsonNode safeAssertions = ProbeAttemptEvidenceFactory.safeAssertions(attempt);
        output.set("assertions", safeAssertions.path("assertions").deepCopy());
        if (safeAssertions.path("payloadTruncated").asBoolean(false)) {
            output.put("payloadTruncated", true);
            output.put("payloadHash", safeAssertions.path("payloadHash").asText());
        }
        return output;
    }

    /** duration·stability의 실행 자원 한계를 HTTP 전에 검증한다. */
    private ProbeConfiguration configuration(JsonNode detail, long runTimeoutMs) {
        Duration within = DslDurations.parse(detail.path("within").asText("15s"));
        Duration interval = DslDurations.parse(detail.path("interval").asText("500ms"));
        String stabilityText = detail.path("stability").path("interval").isTextual()
                ? detail.path("stability").path("interval").asText()
                : detail.path("interval").asText("500ms");
        Duration stabilityInterval = DslDurations.parse(stabilityText);
        int requiredConsecutive = detail.path("stability")
                .path("consecutiveSuccesses").asInt(1);
        if (within.isZero() || interval.isZero() || stabilityInterval.isZero()) {
            throw new IllegalArgumentException("Probe duration과 interval은 0보다 커야 합니다.");
        }
        if (runTimeoutMs > 0 && within.toMillis() > runTimeoutMs) {
            throw new IllegalArgumentException("Probe within은 Run timeout을 넘을 수 없습니다.");
        }
        if (requiredConsecutive < 1) {
            throw new IllegalArgumentException("consecutiveSuccesses는 1 이상이어야 합니다.");
        }
        return new ProbeConfiguration(within, interval, stabilityInterval, requiredConsecutive);
    }

    /** assertion 구조와 JSONPath 문법·길이를 HTTP 전에 검증한다. */
    private List<AssertionSpec> assertions(JsonNode assertionNodes) {
        if (assertionNodes == null || !assertionNodes.isArray()) {
            return List.of();
        }
        if (assertionNodes.size() > MAX_ASSERTIONS) {
            throw new IllegalArgumentException("Probe assertion은 최대 32개까지 허용됩니다.");
        }
        List<AssertionSpec> result = new ArrayList<>();
        for (JsonNode node : assertionNodes) {
            AssertionSpec assertion = JsonAssertions.parseSpec(node);
            if (assertion.jsonPath().getBytes(StandardCharsets.UTF_8).length
                    > MAX_JSON_PATH_BYTES) {
                throw new IllegalArgumentException("assertion JSONPath가 너무 깁니다.");
            }
            JsonPath.compile(assertion.jsonPath());
            result.add(assertion);
        }
        return List.copyOf(result);
    }

    /**
     * optional retryOnStatus를 기본 retry 집합의 부분집합으로 해석한다.
     *
     * @param retryNode Scenario retryOnStatus 노드, 없으면 null
     * @return 이번 Step에 허용된 retry HTTP 상태 집합
     * @throws IllegalArgumentException 배열·정수·부분집합 계약을 위반하면 발생한다
     */
    private Set<Integer> retryStatuses(JsonNode retryNode) {
        if (retryNode == null || retryNode.isNull()) {
            return decisionPolicy.defaultRetryStatuses();
        }
        if (!retryNode.isArray()) {
            throw new IllegalArgumentException("retryOnStatus는 HTTP 상태 정수 배열이어야 합니다.");
        }
        List<Integer> requested = new ArrayList<>();
        for (JsonNode node : retryNode) {
            if (!node.isIntegralNumber()) {
                throw new IllegalArgumentException("retryOnStatus는 HTTP 상태 정수 배열이어야 합니다.");
            }
            requested.add(node.asInt());
        }
        return decisionPolicy.narrowRetryStatuses(requested);
    }

    /**
     * 마지막 관측에서 연속 성공 횟수를 읽는다.
     *
     * @param lastObservation 이전 Waiting snapshot
     * @return 저장된 연속 성공 횟수, 없으면 0
     */
    private int readConsecutive(JsonNode lastObservation) {
        if (lastObservation == null) {
            return 0;
        }
        return lastObservation.path("consecutive").asInt(0);
    }

    /**
     * 새 HTTP 요청을 시작하기 전에 deadline이 지난 경우의 실패 결과를 만든다.
     *
     * @param completedAttempts 이미 Step에 반영된 완료 시도 수
     * @return 추가 HTTP 없이 종료할 deadline 실패
     */
    private StepExecutionResult deadlineBeforeNextAttempt(int completedAttempts) {
        ObjectNode detail = JsonNodeFactory.instance.objectNode();
        detail.put("attempts", completedAttempts);
        detail.put("requestNotStarted", true);
        return new StepExecutionResult.Failed(ProblemCode.PROBE_DEADLINE_EXCEEDED,
                "다음 Probe 요청 전에 제한 시간이 만료됐습니다.", detail);
    }

    /**
     * 기존 identity를 HTTP·secret 재평가 없이 중단 또는 fail-closed 처리한다.
     *
     * @param existing DB가 반환한 기존 권위 attempt
     * @param path 중단 Evidence에 사용할 요청 상대 경로
     * @param interval 일반 재시도 간격
     * @param started Step snapshot에서 복원한 최초 Probe 시작 시각
     * @param within 전체 수렴 허용 기간
     * @param stepDeadline Step·Run 절대 deadline
     * @return 재전송 없는 중단 대기 또는 상태 불일치 오류
     */
    private StepExecutionResult recoverExistingAttempt(
            ScenarioRun run,
            ProbeAttemptStart existing,
            String path,
            Duration interval,
            Instant started,
            Duration within,
            Instant stepDeadline) {
        if (existing.state() == ProbeAttemptStartState.EXISTING_IN_FLIGHT) {
            Instant recoveredStartedAt = existing.intent().requestedAt().isBefore(started)
                    ? existing.intent().requestedAt() : started;
            Instant recoveredDeadline = decisionPolicy.effectiveDeadline(
                    recoveredStartedAt, within, stepDeadline);
            return interruptInFlight(run, existing.intent(), path, interval,
                    recoveredStartedAt, recoveredDeadline);
        }
        if (existing.state() == ProbeAttemptStartState.EXISTING_FINALIZED) {
            return new StepExecutionResult.Errored(ProblemCode.INTERNAL_EXECUTION_ERROR,
                    "Probe Step 상태보다 먼저 확정된 attempt가 발견됐습니다: "
                            + existing.intent().attemptNumber());
        }
        return new StepExecutionResult.Errored(ProblemCode.INTERNAL_EXECUTION_ERROR,
                "read-only Probe attempt 조회가 CREATED 상태를 반환했습니다: "
                        + existing.intent().attemptNumber());
    }

    /**
     * deadline 뒤에는 새 intent를 만들지 않고 기존 같은 identity만 복구한다.
     *
     * <p>read-only find와 optional interrupt를 Step·Run 상태 전이 트랜잭션 안에서
     * 실행해, 과거 HTTP 뒤 남은 in-flight 행을 고아 상태로 두지 않는다.</p>
     *
     * @param candidate 조회할 Run·Step·다음 attempt identity 후보
     * @param path 중단 Evidence에 사용할 요청 상대 경로
     * @param completedAttempts 이미 Step에 반영된 완료 시도 수
     * @return 기존 행 상태를 함께 확정한 deadline 결과
     */
    private StepExecutionResult recoverExpiredAttempt(
            ScenarioRun run, ProbeAttemptIntent candidate, String path, int completedAttempts) {
        return new StepExecutionResult.Transactional(() -> {
            ProbeRunFence fence = ProbeRunFenceFactory.at(run, clockProvider.now(),
                    ProbeRunFence.Boundary.ACTIVE_INTERRUPT);
            ProbeAttemptStart existing = attemptCoordinator
                    .interruptExisting(fence, candidate, path).orElse(null);
            if (existing == null) {
                return deadlineBeforeNextAttempt(completedAttempts);
            }
            if (existing.state() == ProbeAttemptStartState.EXISTING_IN_FLIGHT) {
                ObjectNode detail = JsonNodeFactory.instance.objectNode();
                detail.put("attempts", existing.intent().attemptNumber());
                detail.put("interruptedAttempt", true);
                return new StepExecutionResult.Failed(ProblemCode.PROBE_DEADLINE_EXCEEDED,
                        "결과가 확정되지 않은 Probe 시도를 복구한 뒤 제한 시간으로 종료했습니다.",
                        detail);
            }
            if (existing.state() == ProbeAttemptStartState.EXISTING_FINALIZED) {
                return new StepExecutionResult.Errored(ProblemCode.INTERNAL_EXECUTION_ERROR,
                        "Probe Step 상태보다 먼저 확정된 attempt가 발견됐습니다: "
                                + existing.intent().attemptNumber());
            }
            return new StepExecutionResult.Errored(ProblemCode.INTERNAL_EXECUTION_ERROR,
                    "read-only Probe attempt 조회가 CREATED 상태를 반환했습니다: "
                            + existing.intent().attemptNumber());
        });
    }

    /**
     * 결과가 확정되지 않은 기존 intent를 HTTP 재전송 없이 중단 처리한다.
     *
     * <p>중단 저장과 Step attempt 증가가 같은 Run 트랜잭션에 참여하도록 계산을
     * {@link StepExecutionResult.Transactional}로 지연한다. 다음 시도는 보수적으로
     * 연속 성공 횟수를 초기화하고 최초 Probe 시작 시각을 그대로 이어받는다.</p>
     *
     * @param intent DB가 반환한 결과 미확정 요청 의도
     * @param path 요청 상대 경로
     * @param interval 일반 재시도 간격
     * @param probeStartedAt 최초 Probe 시작 시각
     * @param effectiveDeadline Run·Step 제한 중 더 이른 실제 deadline
     * @return 중단 저장 뒤 대기하거나 deadline으로 실패하는 트랜잭션 결과
     */
    private StepExecutionResult interruptInFlight(
            ScenarioRun run,
            ProbeAttemptIntent intent,
            String path,
            Duration interval,
            Instant probeStartedAt,
            Instant effectiveDeadline) {
        return new StepExecutionResult.Transactional(() -> {
            Instant interruptedAt = clockProvider.now();
            attemptCoordinator.interrupt(
                    ProbeRunFenceFactory.at(run, interruptedAt,
                            ProbeRunFence.Boundary.ACTIVE_INTERRUPT),
                    intent,
                    ProbeAttemptEvidenceFactory.interrupted(intent, path));
            if (!interruptedAt.isBefore(effectiveDeadline)) {
                ObjectNode detail = JsonNodeFactory.instance.objectNode();
                detail.put("attempts", intent.attemptNumber());
                detail.put("interruptedAttempt", true);
                return new StepExecutionResult.Failed(ProblemCode.PROBE_DEADLINE_EXCEEDED,
                        "결과가 확정되지 않은 Probe 시도를 복구하는 동안 제한 시간이 만료됐습니다.",
                        detail);
            }
            Instant nextActionAt = interruptedAt.plus(interval);
            if (nextActionAt.isAfter(effectiveDeadline)) {
                nextActionAt = effectiveDeadline;
            }
            return new StepExecutionResult.Waiting(nextActionAt,
                    interruptedObservation(intent.attemptNumber(), probeStartedAt));
        });
    }

    /**
     * 포트 관측 시각을 DB가 확정한 요청 의도에 맞춰 정규화한다.
     *
     * <p>requestedAt은 항상 intent 값을 사용한다. 포트의 respondedAt이 없거나
     * 그보다 이르면 음수 duration을 만들지 않도록 requestedAt으로 올린다.</p>
     *
     * @param intent DB가 확정한 요청 의도
     * @param observation HTTP 포트가 반환한 관측
     * @return 권위 요청 시각과 단조 응답 시각을 가진 관측
     */
    private ProbeObservation authoritativeObservation(
            ProbeAttemptIntent intent, ProbeObservation observation) {
        Instant respondedAt = observation.respondedAt();
        if (respondedAt == null || respondedAt.isBefore(intent.requestedAt())) {
            respondedAt = intent.requestedAt();
        }
        return new ProbeObservation(intent.requestedAt(), respondedAt,
                observation.statusCode(), observation.responseHash(), observation.body(),
                observation.failureCode());
    }

    /**
     * 결과 미확정 시도를 소비한 뒤 다음 lease에 전달할 안전 snapshot을 만든다.
     *
     * @param attempts 중단 처리까지 소비한 누적 시도 번호
     * @param probeStartedAt 최초 Probe 시작 시각
     * @return 응답 원문 없이 복구 상태만 담은 관측 snapshot
     */
    private JsonNode interruptedObservation(int attempts, Instant probeStartedAt) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("consecutive", 0);
        node.put("attempts", attempts);
        node.put("probeDecision", "INTERRUPTED");
        node.put("probeStartedAt", probeStartedAt.toString());
        return node;
    }

    /**
     * 첫 HTTP 전에 고정한 Probe 시작 시각을 복원한다.
     *
     * <p>첫 호출의 HTTP 시간이 길어도 RunEngine이 나중에 채운 waitStartedAt으로
     * within 창을 다시 시작하지 않도록 lastObservation 값을 우선한다.</p>
     *
     * @param lastObservation 이전 Waiting snapshot
     * @param waitStartedAt 과거 Run 호환용 WAITING 시작 시각
     * @param invocationStartedAt 이번 호출의 HTTP 이전 시각
     * @return 최초 Probe 시작 시각
     */
    private Instant probeStartedAt(JsonNode lastObservation,
                                   Instant waitStartedAt,
                                   Instant invocationStartedAt) {
        if (lastObservation != null && lastObservation.path("probeStartedAt").isTextual()) {
            try {
                return Instant.parse(lastObservation.path("probeStartedAt").asText());
            } catch (RuntimeException exception) {
                // 과거 또는 손상 snapshot은 기존 waitStartedAt 경계로 안전하게 복구한다.
            }
        }
        return waitStartedAt == null ? invocationStartedAt : waitStartedAt;
    }

    /**
     * 연속 성공·시도 횟수·판정을 다음 lease용 안전 snapshot으로 만든다.
     *
     * @param consecutive 현재 연속 성공 횟수
     * @param attempts 누적 attempt 수
     * @param decision 이번 attempt 분류
     * @return 응답 원문을 포함하지 않는 관측 snapshot
     */
    private JsonNode consecutiveObservation(int consecutive, int attempts,
                                            ProbeDecision decision,
                                            Instant probeStartedAt) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("consecutive", consecutive);
        node.put("attempts", attempts);
        node.put("probeDecision", decision.name());
        node.put("probeStartedAt", probeStartedAt.toString());
        return node;
    }

    /**
     * 검증된 Probe 실행 한계를 보존한다.
     *
     * @param within 전체 수렴 허용 기간
     * @param interval 일반 재시도 간격
     * @param stabilityInterval 연속 성공 확인 간격
     * @param requiredConsecutive 필요한 연속 성공 횟수
     */
    private record ProbeConfiguration(
            Duration within,
            Duration interval,
            Duration stabilityInterval,
            int requiredConsecutive) {
    }
}
