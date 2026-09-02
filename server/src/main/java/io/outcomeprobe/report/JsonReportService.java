package io.outcomeprobe.report;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.probe.AssertionOperator;
import io.outcomeprobe.probe.ProbeDecision;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Run 결과를 자동화와 사람이 함께 소비할 수 있는 JSON report로 조립한다.
 *
 * <p>책임: Run 상태, Step 요약, Evidence 기반 상호작용, assertion 결과와 수렴 시간을
 * 하나의 문서로 만든다. 실패를 성공으로 보정하지 않고 관측된 사실만 보고한다.</p>
 * <p>보안 경계: Probe attempt의 payloadSummary는 허용된 필드만 다시 조립한다.
 * JSON이 손상됐을 때 원문 문자열을 report에 넣지 않아 Evidence 저장값이 우회 노출되는
 * 일을 막는다. 현행 redaction 적용 여부를 증명할 수 없는 과거 Step snapshot의
 * expected와 actual도 원문 대신 구조화된 생략 사유만 제공한다.</p>
 */
@Service
public class JsonReportService {

    private static final int MAX_PROBE_SUMMARY_LENGTH = 262_144;
    private static final int MAX_REPORTED_ASSERTIONS = 128;
    private static final int MAX_JSON_PATH_BYTES = 1024;
    private static final int MAX_DIAGNOSTIC_BYTES = 4096;
    private static final String LEGACY_ASSERTION_DIAGNOSTICS_UNAVAILABLE_REASON =
            "LEGACY_STEP_OUTPUT_REDACTION_UNVERIFIED";
    private static final String TRANSMISSION_PLANNED = "PLANNED";
    private static final String TRANSMISSION_UNKNOWN = "UNKNOWN";
    private static final String RETRY_PENDING = "PENDING";
    private static final String PROBE_RESULT_NOT_COMMITTED = "PROBE_RESULT_NOT_COMMITTED";
    private static final String RESULT_NOT_COMMITTED = "RESULT_NOT_COMMITTED";
    private static final Set<String> INTERRUPTED_PAYLOAD_FIELDS = Set.of(
            "attemptNumber", "path", "requestedAt", "transmissionState",
            "retryDecision", "failureCode", "bodyHashUnavailableReason");
    private static final Set<String> INTERRUPTED_ATTRIBUTE_FIELDS = Set.of(
            "attemptNumber", "path", "requestedAt", "transmissionState",
            "retryDecision", "failureCode", "bodyHashUnavailableReason",
            "pathQueryOrFragmentRedacted");
    private static final Set<String> PLANNED_PAYLOAD_FIELDS = Set.of(
            "attemptNumber", "path", "requestedAt", "transmissionState");
    private static final Set<String> PLANNED_ATTRIBUTE_FIELDS = Set.of(
            "attemptNumber", "path", "requestedAt", "transmissionState",
            "pathQueryOrFragmentRedacted");
    private static final Set<String> PROBE_DECISIONS = java.util.Arrays.stream(
            ProbeDecision.values()).map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> ASSERTION_OPERATORS = java.util.Arrays.stream(
            AssertionOperator.values()).map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final RunEngine runEngine;
    private final EvidenceQueryPort evidenceQueryPort;
    private final ObjectMapper objectMapper;

    /**
     * report 조립에 필요한 협업 객체를 주입한다.
     *
     * @param runEngine Run과 Step 조회 엔진
     * @param evidenceQueryPort Evidence 조회 포트
     * @param objectMapper 안전한 Probe 상세 JSON 파서
     */
    public JsonReportService(RunEngine runEngine, EvidenceQueryPort evidenceQueryPort,
                             ObjectMapper objectMapper) {
        this.runEngine = runEngine;
        this.evidenceQueryPort = evidenceQueryPort;
        this.objectMapper = objectMapper;
    }

    /**
     * Run의 JSON report를 생성한다.
     *
     * @param runId Run 식별자
     * @return report 문서, Run이 없으면 empty
     */
    public Optional<Map<String, Object>> buildReport(String runId) {
        Optional<ScenarioRun> runOpt = runEngine.findRun(runId);
        if (runOpt.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRun run = runOpt.get();
        List<RunStepExecution> steps = runEngine.findSteps(runId);
        List<EvidenceEvent> timeline = evidenceQueryPort.timeline(runId);
        return Optional.of(render(run, steps, timeline));
    }

    /**
     * 이미 고정된 Bundle snapshot만 사용해 JSON report를 생성한다.
     *
     * <p>이 overload는 RunEngine이나 EvidenceQueryPort를 호출하지 않는다. 따라서
     * Bundle의 report.json·report.html·JUnit·timeline은 모두 같은 DB 읽기 시점을
     * 설명하며, 생성 도중 도착한 Evidence를 일부 파일에만 섞지 않는다.</p>
     *
     * @param snapshot 같은 읽기 트랜잭션에서 고정한 Bundle snapshot
     * @return JSON 직렬화 가능한 report map
     */
    public Map<String, Object> buildReport(RunBundleSnapshot snapshot) {
        return render(snapshot.run(), snapshot.steps(), snapshot.timeline());
    }

    /**
     * 조회가 끝난 Run·Step·Evidence만 사용해 report 구조를 순수 조립한다.
     *
     * @param run 고정된 Run snapshot
     * @param steps 고정된 Step snapshot
     * @param timeline 고정된 Evidence snapshot
     * @return JSON 직렬화 가능한 report map
     */
    private Map<String, Object> render(ScenarioRun run, List<RunStepExecution> steps,
                                       List<EvidenceEvent> timeline) {
        ProbeReportData probeData = probeReportData(timeline, steps);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", run.id());

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("name", run.scenarioName());
        scenario.put("version", run.scenarioVersion());
        scenario.put("contentHash", run.scenarioHash());
        report.put("scenario", scenario);

        report.put("targetProfile", run.targetProfileName());
        report.put("seed", run.seed());
        report.put("status", run.status().name());
        report.put("startedAt", instantText(run.startedAt()));
        report.put("finishedAt", instantText(run.finishedAt()));
        if (run.startedAt() != null && run.finishedAt() != null) {
            report.put("durationMs", Duration.between(run.startedAt(), run.finishedAt()).toMillis());
        }
        report.put("convergenceDurationMs", convergenceDurationMs(timeline).orElse(null));

        long stepsPassed = steps.stream().filter(step -> step.status() == StepStatus.PASSED).count();
        long stepsFailed = steps.stream()
                .filter(step -> step.status() == StepStatus.FAILED || step.status() == StepStatus.ERROR)
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stepsPassed", stepsPassed);
        summary.put("stepsFailed", stepsFailed);
        summary.put("providerCommits", count(timeline, EvidenceType.PROVIDER_OPERATION_COMMITTED));
        summary.put("callbackDeliveries", count(timeline, EvidenceType.CALLBACK_DELIVERY_SUCCEEDED));
        summary.put("callbackOmitted", count(timeline, EvidenceType.CALLBACK_OMITTED_BY_SCENARIO));
        summary.put("probeAttempts", count(timeline, EvidenceType.PROBE_ATTEMPTED));
        report.put("summary", summary);
        report.put("assertions", probeData.assertions());
        report.put("probeAttempts", probeData.attempts());

        List<Map<String, Object>> failures = timeline.stream()
                .filter(event -> event.type() == EvidenceType.STEP_FAILED
                        || event.type() == EvidenceType.RUN_FAILED
                        || event.type() == EvidenceType.RUN_ERROR
                        || event.type() == EvidenceType.EVIDENCE_ASSERTION_FAILED
                        || event.type() == EvidenceType.PROBE_ASSERTION_FAILED)
                .map(this::failureEntry)
                .toList();
        report.put("failures", failures);

        if (run.problemCode() != null) {
            report.put("problemCode", run.problemCode());
        }
        String explanation = explain(run, summary);
        if (explanation != null) {
            report.put("explanation", explanation);
        }

        List<String> limitations = new ArrayList<>();
        limitations.add("이 report는 Reference Consumer 또는 등록된 Target의 HTTP 관측만 근거로 합니다.");
        limitations.add("OutcomeProbe는 Callback exactly-once를 보장하지 않습니다.");
        if (probeData.interrupted()) {
            limitations.add("일부 Probe attempt는 결과가 확정되지 않아 복구 처리되었으며 "
                    + "transmissionState를 UNKNOWN으로 표시했습니다.");
        }
        if (probeData.incomplete()) {
            limitations.add("일부 Probe attempt 상세 JSON이 없거나 손상되어 허용된 메타데이터만 표시했습니다.");
        }
        report.put("limitations", List.copyOf(limitations));
        return report;
    }

    /**
     * 고정된 Timeline에서 특정 Evidence 유형의 건수를 계산한다.
     *
     * @param timeline 고정된 Evidence 목록
     * @param type 집계할 Evidence 유형
     * @return 일치하는 Evidence 수
     */
    private long count(List<EvidenceEvent> timeline, EvidenceType type) {
        return timeline.stream().filter(event -> event.type() == type).count();
    }

    /**
     * Probe attempt와 assertion을 허용 목록 기반 report 자료로 변환한다.
     */
    private ProbeReportData probeReportData(List<EvidenceEvent> timeline,
                                            List<RunStepExecution> steps) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        List<Map<String, Object>> assertions = new ArrayList<>();
        Map<ProbeAttemptKey, EvidenceEvent> requests = new LinkedHashMap<>();
        Map<ProbeAttemptKey, EvidenceEvent> interruptions = new LinkedHashMap<>();
        Map<ProbeAttemptKey, Boolean> matchedRequests = new LinkedHashMap<>();
        boolean incomplete = false;
        boolean interrupted = false;

        for (EvidenceEvent event : timeline) {
            if (event.type() == EvidenceType.PROBE_ATTEMPTED) {
                ProbeAttemptKey key = probeAttemptKey(event);
                requests.putIfAbsent(key, event);
                matchedRequests.putIfAbsent(key, false);
                continue;
            }
            if (event.type() == EvidenceType.PROBE_ATTEMPT_INTERRUPTED) {
                interruptions.putIfAbsent(probeAttemptKey(event), event);
                continue;
            }
            if (event.type() != EvidenceType.PROBE_RESPONSE_RECEIVED) {
                continue;
            }
            ProbeAttemptDetail detail = parseProbeAttemptDetail(event.payloadSummary());
            ProbeAttemptKey key = probeAttemptKey(event);
            EvidenceEvent request = requests.get(key);
            matchedRequests.put(key, true);
            Map<String, Object> attempt = reportProbeAttempt(event, request, detail, false);
            attempts.add(attempt);
            addReportedAssertions(assertions, event.stepId(), attempt, detail);
            incomplete |= !detail.available() || detail.truncated();
        }

        // 응답 결과가 이미 commit된 같은 attempt는 그 결과를 우선하고 복구 표식을 중복 보고하지 않는다.
        for (Map.Entry<ProbeAttemptKey, EvidenceEvent> entry : interruptions.entrySet()) {
            if (Boolean.TRUE.equals(matchedRequests.get(entry.getKey()))) {
                continue;
            }
            EvidenceEvent interruption = entry.getValue();
            EvidenceEvent request = requests.get(entry.getKey());
            ProbeInterruptedDetail detail = parseProbeInterruptedDetail(interruption, request);
            attempts.add(reportInterruptedAttempt(interruption, request, detail));
            matchedRequests.put(entry.getKey(), true);
            interrupted = true;
            incomplete |= !detail.available();
        }

        // 새 계약에서는 요청·응답이 같은 transaction에 저장되지만, 과거 Run과 손상된
        // 자료도 fail-closed로 표시한다. 응답 상세을 담던 legacy PROBE_ATTEMPTED도 지원한다.
        for (Map.Entry<ProbeAttemptKey, EvidenceEvent> entry : requests.entrySet()) {
            if (Boolean.TRUE.equals(matchedRequests.get(entry.getKey()))) {
                continue;
            }
            EvidenceEvent request = entry.getValue();
            ProbePlannedDetail planned = parseProbePlannedDetail(request);
            if (planned.available() || declaresPlannedRequest(request)) {
                attempts.add(reportPlannedAttempt(request, planned));
                incomplete |= !planned.available();
                continue;
            }
            ProbeAttemptDetail legacy = parseProbeAttemptDetail(request.payloadSummary());
            Map<String, Object> attempt = legacy.available()
                    ? reportProbeAttempt(request, request, legacy, true)
                    : reportProbeAttempt(null, request, legacy, false);
            attempts.add(attempt);
            addReportedAssertions(assertions, request.stepId(), attempt, legacy);
            incomplete |= !legacy.available() || legacy.truncated();
        }

        attempts.sort(Comparator.comparingLong(
                value -> ((Number) value.get("sequence")).longValue()));
        if (assertions.isEmpty()) {
            assertions.addAll(assertionsFromStepOutputs(steps));
        }
        return new ProbeReportData(
                List.copyOf(attempts), List.copyOf(assertions), incomplete, interrupted);
    }

    /**
     * 응답 Evidence와 같은 attempt의 요청 Evidence를 하나의 report 항목으로 합친다.
     */
    private Map<String, Object> reportProbeAttempt(EvidenceEvent response,
                                                    EvidenceEvent request,
                                                    ProbeAttemptDetail detail,
                                                    boolean legacy) {
        EvidenceEvent reportedEvent = response == null ? request : response;
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("sequence", reportedEvent.sequence());
        attempt.put("requestSequence", request == null ? null : request.sequence());
        attempt.put("stepId", reportedEvent.stepId());
        attempt.put("logicalTimeMs", reportedEvent.logicalTimeMs());
        String requestedAt = legacy
                ? validatedInstantText(detail.requestedAt())
                : request == null
                        ? validatedInstantText(detail.requestedAt())
                        : instantText(request.observedAt());
        String respondedAt = legacy
                ? firstNonBlank(validatedInstantText(detail.respondedAt()),
                        instantText(reportedEvent.observedAt()))
                : response == null ? null : instantText(response.observedAt());
        attempt.put("requestedAt", requestedAt);
        attempt.put("respondedAt", respondedAt);
        // observedAt은 기존 report 소비자를 위한 응답 관측 시각 alias다.
        attempt.put("observedAt", respondedAt);
        putNumber(attempt, "attemptNumber", detail.attemptNumber(),
                reportedEvent.attributes().get("attemptNumber"));
        attempt.put("status", reportedEvent.attributes().get("status"));
        putNumber(attempt, "statusCode", detail.statusCode(),
                reportedEvent.attributes().get("status"));
        attempt.put("bodyHash", firstNonBlank(reportedEvent.bodyHash(), detail.responseHash()));
        attempt.put("bodyHashUnavailableReason",
                reportedEvent.attributes().get("bodyHashUnavailableReason"));
        attempt.put("retryDecision", firstNonBlank(detail.retryDecision(),
                reportedEvent.attributes().get("retryDecision")));
        attempt.put("failureCode", firstNonBlank(detail.failureCode(),
                reportedEvent.attributes().get("failureCode")));
        attempt.put("path", reportedEvent.attributes().get("path"));
        attempt.put("assertions", detail.assertions());
        attempt.put("detailsAvailable", detail.available());
        attempt.put("detailsTruncated", detail.truncated());
        return attempt;
    }

    /**
     * 아직 응답이나 중단 결과가 없는 Probe 요청 의도를 report 항목으로 만든다.
     *
     * <p>요청 Evidence의 관측 시각만 권위 있는 요청 시각으로 사용하며, 응답·판정 관련
     * 값은 관측 전이므로 모두 비워 둔다.</p>
     *
     * @param request 결과가 아직 연결되지 않은 요청 Evidence
     * @param detail 검증된 요청 의도 상세
     * @return 전송 계획 상태와 요청 정보만 포함한 Probe attempt report 항목
     */
    private Map<String, Object> reportPlannedAttempt(EvidenceEvent request,
                                                      ProbePlannedDetail detail) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("sequence", request.sequence());
        attempt.put("requestSequence", request.sequence());
        attempt.put("stepId", request.stepId());
        attempt.put("logicalTimeMs", request.logicalTimeMs());
        attempt.put("requestedAt", instantText(request.observedAt()));
        attempt.put("respondedAt", null);
        attempt.put("observedAt", null);
        attempt.put("interruptedAt", null);
        putNumber(attempt, "attemptNumber", detail.attemptNumber(),
                request.attributes().get("attemptNumber"));
        attempt.put("status", null);
        attempt.put("statusCode", null);
        attempt.put("bodyHash", null);
        attempt.put("bodyHashUnavailableReason", null);
        attempt.put("transmissionState", TRANSMISSION_PLANNED);
        attempt.put("retryDecision", null);
        attempt.put("failureCode", null);
        attempt.put("path", detail.available() ? detail.path() : null);
        attempt.put("assertions", List.of());
        attempt.put("detailsAvailable", detail.available());
        attempt.put("detailsTruncated", false);
        return attempt;
    }

    /**
     * 결과 commit 전에 중단된 Probe 요청을 응답 관측값 없이 report 항목으로 만든다.
     *
     * <p>중단 Evidence의 유형 자체로 확정할 수 있는 값만 고정하고, 검증된 허용 목록
     * payload 밖의 값은 report에 복원하지 않는다.</p>
     *
     * @param interruption 결과 미확정 중단 Evidence
     * @param request 같은 Step·attempt 번호의 요청 Evidence, 없으면 null
     * @param detail 검증된 중단 상세
     * @return 응답 관련 필드가 null인 Probe attempt report 항목
     */
    private Map<String, Object> reportInterruptedAttempt(EvidenceEvent interruption,
                                                          EvidenceEvent request,
                                                          ProbeInterruptedDetail detail) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("sequence", interruption.sequence());
        attempt.put("requestSequence", request == null ? null : request.sequence());
        attempt.put("stepId", interruption.stepId());
        attempt.put("logicalTimeMs", interruption.logicalTimeMs());
        String requestedAt = request == null
                ? validatedInstantText(detail.requestedAt())
                : instantText(request.observedAt());
        attempt.put("requestedAt", requestedAt);
        attempt.put("interruptedAt", instantText(interruption.observedAt()));
        attempt.put("respondedAt", null);
        attempt.put("observedAt", null);
        putNumber(attempt, "attemptNumber", detail.attemptNumber(),
                interruption.attributes().get("attemptNumber"));
        attempt.put("status", null);
        attempt.put("statusCode", null);
        attempt.put("bodyHash", null);
        attempt.put("bodyHashUnavailableReason", RESULT_NOT_COMMITTED);
        attempt.put("transmissionState", TRANSMISSION_UNKNOWN);
        attempt.put("retryDecision", RETRY_PENDING);
        attempt.put("failureCode", PROBE_RESULT_NOT_COMMITTED);
        attempt.put("path", detail.available() ? detail.path() : null);
        attempt.put("assertions", List.of());
        attempt.put("detailsAvailable", detail.available());
        attempt.put("detailsTruncated", false);
        return attempt;
    }

    /** 같은 Probe attempt의 assertion을 top-level report 목록에도 추가한다. */
    private void addReportedAssertions(List<Map<String, Object>> assertions,
                                       String stepId,
                                       Map<String, Object> attempt,
                                       ProbeAttemptDetail detail) {
        for (Map<String, Object> assertion : detail.assertions()) {
            Map<String, Object> reported = new LinkedHashMap<>();
            reported.put("stepId", stepId);
            reported.put("attemptNumber", attempt.get("attemptNumber"));
            reported.putAll(assertion);
            assertions.add(reported);
        }
    }

    /** Step과 attempt 번호로 요청·응답 Evidence를 안전하게 연결한다. */
    private ProbeAttemptKey probeAttemptKey(EvidenceEvent event) {
        return new ProbeAttemptKey(event.stepId(), event.attributes().get("attemptNumber"));
    }

    /**
     * 응답이 아직 없는 요청 Evidence의 strict 허용 목록을 검증한다.
     *
     * @param event 전송 계획 상태의 Probe 요청 Evidence
     * @return payload와 attributes가 모두 일치하면 사용할 수 있는 요청 의도 상세
     */
    private ProbePlannedDetail parseProbePlannedDetail(EvidenceEvent event) {
        String payloadSummary = event.payloadSummary();
        if (payloadSummary == null || payloadSummary.isBlank()
                || payloadSummary.length() > MAX_PROBE_SUMMARY_LENGTH) {
            return ProbePlannedDetail.unavailable();
        }
        try {
            JsonNode root = objectMapper.readTree(payloadSummary);
            if (!isValidPlannedSummary(root, event)) {
                return ProbePlannedDetail.unavailable();
            }
            return new ProbePlannedDetail(
                    integerOrNull(root.get("attemptNumber")),
                    textOrNull(root.get("requestedAt")),
                    textOrNull(root.get("path")), true);
        } catch (RuntimeException exception) {
            return ProbePlannedDetail.unavailable();
        }
    }

    /**
     * 요청 payload와 attributes가 응답 사실을 포함하지 않는 PLANNED 계약인지 확인한다.
     *
     * @param root 요청 Evidence payload JSON
     * @param event payload와 함께 저장된 요청 Evidence
     * @return 필드 허용 목록·계획 상태·요청 시각 일치가 모두 성립하면 true
     */
    private boolean isValidPlannedSummary(JsonNode root, EvidenceEvent event) {
        if (root == null || !root.isObject()
                || root.size() != PLANNED_PAYLOAD_FIELDS.size()
                || root.properties().stream()
                        .anyMatch(entry -> !PLANNED_PAYLOAD_FIELDS.contains(entry.getKey()))
                || event.bodyHash() != null
                || event.attributes().keySet().stream()
                        .anyMatch(key -> !PLANNED_ATTRIBUTE_FIELDS.contains(key))) {
            return false;
        }
        Integer attemptNumber = integerOrNull(root.get("attemptNumber"));
        String path = textOrNull(root.get("path"));
        String requestedAt = textOrNull(root.get("requestedAt"));
        Map<String, String> attributes = event.attributes();
        return attemptNumber != null && attemptNumber >= 1
                && isBoundedText(root.get("path"), MAX_JSON_PATH_BYTES)
                && path.indexOf('?') < 0 && path.indexOf('#') < 0
                && validatedInstantText(requestedAt) != null
                && TRANSMISSION_PLANNED.equals(textOrNull(root.get("transmissionState")))
                && String.valueOf(attemptNumber).equals(attributes.get("attemptNumber"))
                && path.equals(attributes.get("path"))
                && requestedAt.equals(attributes.get("requestedAt"))
                && TRANSMISSION_PLANNED.equals(attributes.get("transmissionState"))
                && event.observedAt() != null
                && requestedAt.equals(instantText(event.observedAt()))
                && (!attributes.containsKey("pathQueryOrFragmentRedacted")
                        || "true".equals(attributes.get("pathQueryOrFragmentRedacted")));
    }

    /**
     * 손상된 payload라도 새 PLANNED 계약을 선언했다면 legacy 완료 attempt로 해석하지 않는다.
     *
     * @param event 완료 결과가 연결되지 않은 요청 Evidence
     * @return attributes 또는 JSON payload가 PLANNED 상태를 선언하면 true
     */
    private boolean declaresPlannedRequest(EvidenceEvent event) {
        if (TRANSMISSION_PLANNED.equals(event.attributes().get("transmissionState"))) {
            return true;
        }
        String payloadSummary = event.payloadSummary();
        if (payloadSummary == null || payloadSummary.isBlank()
                || payloadSummary.length() > MAX_PROBE_SUMMARY_LENGTH) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(payloadSummary);
            return root != null && root.isObject()
                    && TRANSMISSION_PLANNED.equals(
                            textOrNull(root.get("transmissionState")));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 중단 Evidence의 허용 목록 payload를 검증해 report용 안전 상세로 변환한다.
     *
     * @param event 결과 commit 전 중단을 나타내는 Evidence
     * @param request 같은 Step·attempt 번호의 요청 Evidence, 없으면 null
     * @return 허용 목록과 속성이 모두 일치하면 사용할 수 있는 중단 상세
     */
    private ProbeInterruptedDetail parseProbeInterruptedDetail(EvidenceEvent event,
                                                                 EvidenceEvent request) {
        String payloadSummary = event.payloadSummary();
        if (payloadSummary == null || payloadSummary.isBlank()
                || payloadSummary.length() > MAX_PROBE_SUMMARY_LENGTH) {
            return ProbeInterruptedDetail.unavailable();
        }
        try {
            JsonNode root = objectMapper.readTree(payloadSummary);
            if (!isValidInterruptedSummary(root, event, request)) {
                return ProbeInterruptedDetail.unavailable();
            }
            return new ProbeInterruptedDetail(
                    integerOrNull(root.get("attemptNumber")),
                    textOrNull(root.get("requestedAt")),
                    textOrNull(root.get("path")), true);
        } catch (RuntimeException exception) {
            return ProbeInterruptedDetail.unavailable();
        }
    }

    /**
     * 중단 payload와 attributes가 응답 사실을 포함하지 않는 고정 계약인지 확인한다.
     *
     * @param root 중단 Evidence payload JSON
     * @param event payload와 함께 저장된 Evidence
     * @param request 같은 Step·attempt 번호의 요청 Evidence, 없으면 null
     * @return 필드 허용 목록·고정 상태·속성 일치가 모두 성립하면 true
     */
    private boolean isValidInterruptedSummary(JsonNode root, EvidenceEvent event,
                                               EvidenceEvent request) {
        if (root == null || !root.isObject()
                || root.size() != INTERRUPTED_PAYLOAD_FIELDS.size()
                || root.properties().stream()
                        .anyMatch(entry -> !INTERRUPTED_PAYLOAD_FIELDS.contains(entry.getKey()))
                || event.bodyHash() != null
                || event.attributes().keySet().stream()
                        .anyMatch(key -> !INTERRUPTED_ATTRIBUTE_FIELDS.contains(key))) {
            return false;
        }
        Integer attemptNumber = integerOrNull(root.get("attemptNumber"));
        String path = textOrNull(root.get("path"));
        String requestedAt = textOrNull(root.get("requestedAt"));
        Map<String, String> attributes = event.attributes();
        return attemptNumber != null && attemptNumber >= 1
                && isBoundedText(root.get("path"), MAX_JSON_PATH_BYTES)
                && path.indexOf('?') < 0 && path.indexOf('#') < 0
                && validatedInstantText(requestedAt) != null
                && TRANSMISSION_UNKNOWN.equals(textOrNull(root.get("transmissionState")))
                && RETRY_PENDING.equals(textOrNull(root.get("retryDecision")))
                && PROBE_RESULT_NOT_COMMITTED.equals(textOrNull(root.get("failureCode")))
                && RESULT_NOT_COMMITTED.equals(
                        textOrNull(root.get("bodyHashUnavailableReason")))
                && String.valueOf(attemptNumber).equals(attributes.get("attemptNumber"))
                && path.equals(attributes.get("path"))
                && requestedAt.equals(attributes.get("requestedAt"))
                && TRANSMISSION_UNKNOWN.equals(attributes.get("transmissionState"))
                && RETRY_PENDING.equals(attributes.get("retryDecision"))
                && PROBE_RESULT_NOT_COMMITTED.equals(attributes.get("failureCode"))
                && RESULT_NOT_COMMITTED.equals(
                        attributes.get("bodyHashUnavailableReason"))
                && (request == null || request.observedAt() != null
                        && requestedAt.equals(instantText(request.observedAt())))
                && (!attributes.containsKey("pathQueryOrFragmentRedacted")
                        || "true".equals(attributes.get("pathQueryOrFragmentRedacted")));
    }

    /**
     * Probe attempt의 구조화된 상세 JSON에서 허용된 필드만 읽는다.
     */
    private ProbeAttemptDetail parseProbeAttemptDetail(String payloadSummary) {
        if (payloadSummary == null || payloadSummary.isBlank()
                || payloadSummary.length() > MAX_PROBE_SUMMARY_LENGTH) {
            return ProbeAttemptDetail.unavailable();
        }
        try {
            JsonNode root = objectMapper.readTree(payloadSummary);
            if (!isValidProbeSummary(root)) {
                return ProbeAttemptDetail.unavailable();
            }
            List<Map<String, Object>> assertions = new ArrayList<>();
            JsonNode assertionNodes = root.path("assertions");
            if (assertionNodes.isArray()) {
                for (JsonNode node : assertionNodes) {
                    Map<String, Object> assertion = new LinkedHashMap<>();
                    assertion.put("jsonPath", textOrNull(node.get("jsonPath")));
                    assertion.put("operator", textOrNull(node.get("operator")));
                    assertion.put("expected", jsonValue(node.get("expected")));
                    assertion.put("actual", jsonValue(node.get("actual")));
                    assertion.put("passed", booleanOrNull(node.get("passed")));
                    assertions.add(assertion);
                }
            }
            return new ProbeAttemptDetail(integerOrNull(root.get("attemptNumber")),
                    integerOrNull(root.get("statusCode")), textOrNull(root.get("responseHash")),
                    textOrNull(root.get("retryDecision")), textOrNull(root.get("failureCode")),
                    textOrNull(root.get("requestedAt")), textOrNull(root.get("respondedAt")),
                    List.copyOf(assertions), true,
                    root.path("payloadTruncated").asBoolean(false));
        } catch (RuntimeException exception) {
            return ProbeAttemptDetail.unavailable();
        }
    }

    /**
     * Probe 상세 JSON이 고정된 report 계약을 충족하는지 확인한다.
     */
    private boolean isValidProbeSummary(JsonNode root) {
        Integer attemptNumber = root == null ? null : integerOrNull(root.get("attemptNumber"));
        Integer statusCode = root == null ? null : integerOrNull(root.get("statusCode"));
        String retryDecision = root == null ? null : textOrNull(root.get("retryDecision"));
        if (root == null || !root.isObject()
                || attemptNumber == null || attemptNumber < 1
                || statusCode == null || statusCode < 0 || statusCode > 599
                || !root.path("assertions").isArray()
                || root.path("assertions").size() > MAX_REPORTED_ASSERTIONS
                || !PROBE_DECISIONS.contains(retryDecision)
                || !isSha256OrNull(root.get("responseHash"))
                || !isFailureCodeOrNull(root.get("failureCode"))
                || !isTimestampPair(root.get("requestedAt"), root.get("respondedAt"))
                || !isOptionalBoolean(root.get("payloadTruncated"))
                || !isOptionalSha256(root.get("payloadHash"))) {
            return false;
        }
        for (JsonNode assertion : root.path("assertions")) {
            if (!assertion.isObject()
                    || !isBoundedText(assertion.get("jsonPath"), MAX_JSON_PATH_BYTES)
                    || !ASSERTION_OPERATORS.contains(textOrNull(assertion.get("operator")))
                    || !isSafeDiagnostic(assertion.get("expected"))
                    || !isSafeDiagnostic(assertion.get("actual"))
                    || booleanOrNull(assertion.get("passed")) == null) {
                return false;
            }
        }
        return true;
    }

    /** assertion expected/actual은 null 또는 4KiB 이하 문자열만 허용한다. */
    private boolean isSafeDiagnostic(JsonNode node) {
        return node != null && (node.isNull()
                || isBoundedText(node, MAX_DIAGNOSTIC_BYTES));
    }

    /** UTF-8 바이트 상한 안의 문자열인지 확인한다. */
    private boolean isBoundedText(JsonNode node, int maximumBytes) {
        return node != null && node.isString()
                && node.asText().getBytes(StandardCharsets.UTF_8).length <= maximumBytes;
    }

    /** 응답 hash는 null 또는 소문자 SHA-256이어야 한다. */
    private boolean isSha256OrNull(JsonNode node) {
        return node != null && (node.isNull()
                || node.isString() && node.asText().matches("^[0-9a-f]{64}$"));
    }

    /** optional hash가 없거나 소문자 SHA-256인지 확인한다. */
    private boolean isOptionalSha256(JsonNode node) {
        return node == null || isSha256OrNull(node);
    }

    /** 실패 코드는 null 또는 제한된 대문자 식별자만 허용한다. */
    private boolean isFailureCodeOrNull(JsonNode node) {
        return node != null && (node.isNull()
                || node.isString() && node.asText().matches("^[A-Z][A-Z0-9_]{0,79}$"));
    }

    /** 요청·응답 시각은 함께 없거나 올바른 순서의 ISO Instant여야 한다. */
    private boolean isTimestampPair(JsonNode requestedNode, JsonNode respondedNode) {
        if (requestedNode == null && respondedNode == null) {
            return true;
        }
        if (requestedNode == null || respondedNode == null
                || !requestedNode.isString() || !respondedNode.isString()) {
            return false;
        }
        try {
            Instant requested = Instant.parse(requestedNode.asText());
            Instant responded = Instant.parse(respondedNode.asText());
            return !responded.isBefore(requested);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** optional boolean field가 없거나 boolean인지 확인한다. */
    private boolean isOptionalBoolean(JsonNode node) {
        return node == null || node.isBoolean();
    }

    /**
     * 새 형식의 Probe Evidence가 없는 과거 Run에서 최종 Step snapshot을 보조 근거로 사용한다.
     *
     * <p>과거 Step output은 현행 redaction 계약을 거쳤는지 증명할 수 없으므로 expected와
     * actual 원문을 복원하지 않는다. 대신 진단값을 사용할 수 없는 이유를 구조화해
     * 소비자가 실제 null과 보안상 생략된 값을 구분할 수 있게 한다.</p>
     */
    private List<Map<String, Object>> assertionsFromStepOutputs(List<RunStepExecution> steps) {
        List<Map<String, Object>> assertions = new ArrayList<>();
        for (RunStepExecution step : steps) {
            if (step.stepType() != StepType.PROBE_EVENTUALLY || step.outputSnapshot() == null) {
                continue;
            }
            JsonNode nodes = step.outputSnapshot().path("assertions");
            if (!nodes.isArray()) {
                continue;
            }
            for (JsonNode node : nodes) {
                if (!node.isObject()) {
                    continue;
                }
                Map<String, Object> assertion = new LinkedHashMap<>();
                assertion.put("stepId", step.stepId());
                assertion.put("attemptNumber", step.attemptCount());
                assertion.put("jsonPath", textOrNull(node.get("jsonPath")));
                assertion.put("operator", textOrNull(node.get("operator")));
                // 과거 snapshot은 redaction 적용 여부를 증명할 수 없어 진단 원문을 공개하지 않는다.
                assertion.put("expected", null);
                assertion.put("actual", null);
                assertion.put("diagnosticsAvailable", false);
                assertion.put("diagnosticsUnavailableReason",
                        LEGACY_ASSERTION_DIAGNOSTICS_UNAVAILABLE_REASON);
                assertion.put("passed", booleanOrNull(node.get("passed")));
                assertions.add(assertion);
            }
        }
        return assertions;
    }

    /**
     * 최초 Target trigger 또는 Callback 시도부터 안정화 Probe 통과까지의 시간을 계산한다.
     */
    private Optional<Long> convergenceDurationMs(List<EvidenceEvent> timeline) {
        Optional<EvidenceEvent> started = timeline.stream()
                .filter(event -> event.type() == EvidenceType.TARGET_REQUEST_SENT
                        || event.type() == EvidenceType.CALLBACK_DELIVERY_ATTEMPTED)
                .filter(event -> event.observedAt() != null)
                .min(Comparator.comparingLong(EvidenceEvent::sequence));
        if (started.isEmpty()) {
            return Optional.empty();
        }
        EvidenceEvent start = started.get();
        Optional<EvidenceEvent> converged = timeline.stream()
                .filter(event -> event.sequence() > start.sequence())
                .filter(event -> event.type() == EvidenceType.PROBE_ASSERTION_PASSED)
                .filter(event -> event.attributes().containsKey("attemptNumber"))
                .filter(event -> event.observedAt() != null)
                .min(Comparator.comparingLong(EvidenceEvent::sequence));
        if (converged.isEmpty()) {
            // 과거 Evidence는 stability 중간 통과와 최종 통과를 구분하지 못하므로
            // 최종 Probe Step 상태 전이를 안전한 호환 근거로 사용한다.
            converged = timeline.stream()
                    .filter(event -> event.sequence() > start.sequence())
                    .filter(event -> event.type() == EvidenceType.STEP_PASSED)
                    .filter(event -> StepType.PROBE_EVENTUALLY.name()
                            .equals(event.attributes().get("stepType")))
                    .filter(event -> event.observedAt() != null)
                    .min(Comparator.comparingLong(EvidenceEvent::sequence));
        }
        if (converged.isEmpty() || converged.get().observedAt().isBefore(start.observedAt())) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(start.observedAt(), converged.get().observedAt()).toMillis());
    }

    /**
     * 실패 Evidence를 report의 실패 항목으로 변환한다.
     */
    private Map<String, Object> failureEntry(EvidenceEvent event) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("type", event.type().name());
        failure.put("stepId", event.stepId());
        failure.put("attributes", event.attributes());
        failure.put("bodyHash", event.bodyHash());
        return failure;
    }

    /**
     * 실패 원인을 규칙 기반으로 설명한다.
     */
    private String explain(ScenarioRun run, Map<String, Object> summary) {
        if (!"FAILED".equals(run.status().name())) {
            return null;
        }
        Object commitsValue = summary.getOrDefault("providerCommits", 0L);
        Object deliveriesValue = summary.getOrDefault("callbackDeliveries", 0L);
        Object omittedValue = summary.getOrDefault("callbackOmitted", 0L);
        Object probesValue = summary.getOrDefault("probeAttempts", 0L);

        StringBuilder explanation = new StringBuilder();
        explanation.append("관측: Provider commit ").append(commitsValue).append("회, ");
        explanation.append("Callback 전달 ").append(deliveriesValue).append("회, ");
        explanation.append("생략 ").append(omittedValue).append("회, ");
        explanation.append("Probe 시도 ").append(probesValue).append("회. ");
        long commits = ((Number) commitsValue).longValue();
        long probes = ((Number) probesValue).longValue();
        if (commits > 0 && probes > 0) {
            explanation.append("가능성이 높은 원인: Provider는 성공으로 commit했지만 ")
                    .append("Target의 조회 또는 Callback 수렴 경로로 최종 상태가 전달되지 못했습니다. ")
                    .append("timeout을 즉시 실패로 확정했거나 상태 조회 복구가 누락됐을 수 있습니다.");
        } else if ("PROVIDER_EXPECTATION_UNSATISFIED".equals(run.problemCode())) {
            explanation.append("가능성이 높은 원인: Target이 기대한 Provider 호출을 수행하지 않았습니다.");
        } else {
            explanation.append("가능성이 높은 원인: Scenario 기대와 실제 상호작용이 일치하지 않습니다. ")
                    .append("Timeline에서 실패 Step과 Evidence를 확인하세요.");
        }
        return explanation.toString();
    }

    /**
     * JSON 단순 값을 Java report 값으로 안전하게 변환한다.
     */
    private Object jsonValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(jsonValue(value)));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.properties().forEach(entry -> values.put(entry.getKey(), jsonValue(entry.getValue())));
            return values;
        }
        return null;
    }

    /**
     * 정수 JSON 값을 읽는다.
     */
    private Integer integerOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    /**
     * 문자열 JSON 값을 읽는다.
     */
    private String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    /**
     * 불리언 JSON 값을 읽는다.
     */
    private Boolean booleanOrNull(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    /**
     * JSON 숫자 또는 Evidence 문자열 숫자를 우선순위에 따라 report에 넣는다.
     */
    private void putNumber(Map<String, Object> target, String key, Integer preferred,
                           String fallback) {
        if (preferred != null) {
            target.put(key, preferred);
            return;
        }
        try {
            target.put(key, fallback == null ? null : Integer.valueOf(fallback));
        } catch (NumberFormatException exception) {
            target.put(key, null);
        }
    }

    /**
     * 비어 있지 않은 첫 문자열을 반환한다.
     */
    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /**
     * 시각을 ISO-8601 문자열로 변환한다.
     */
    private String instantText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** 외부 JSON의 시각 문자열을 ISO-8601 Instant로 검증해 정규화한다. */
    private String validatedInstantText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Probe report 조립 중간 결과다.
     *
     * @param attempts Probe 시도 목록
     * @param assertions assertion 판정 목록
     * @param incomplete 상세가 없거나 손상된 시도가 있으면 true
     * @param interrupted 결과 commit 전에 중단된 시도를 보고했으면 true
     */
    private record ProbeReportData(List<Map<String, Object>> attempts,
                                   List<Map<String, Object>> assertions,
                                   boolean incomplete,
                                   boolean interrupted) {
    }

    /**
     * 아직 응답이나 중단 결과가 없는 Probe 요청 의도의 안전한 정보다.
     *
     * @param attemptNumber 시도 번호
     * @param requestedAt 요청 시작 시각
     * @param path query와 fragment가 제거된 요청 경로
     * @param available payload와 attributes의 허용 목록 검증을 통과했으면 true
     */
    private record ProbePlannedDetail(Integer attemptNumber,
                                      String requestedAt,
                                      String path,
                                      boolean available) {

        /** 검증할 수 없는 요청 의도 payload를 나타내는 안전한 대체값을 만든다. */
        private static ProbePlannedDetail unavailable() {
            return new ProbePlannedDetail(null, null, null, false);
        }
    }

    /**
     * 결과 commit 전에 중단된 Probe attempt의 안전한 의도 정보다.
     *
     * @param attemptNumber 시도 번호
     * @param requestedAt 요청 시작 시각
     * @param path query와 fragment가 제거된 요청 경로
     * @param available payload와 attributes의 허용 목록 검증을 통과했으면 true
     */
    private record ProbeInterruptedDetail(Integer attemptNumber,
                                          String requestedAt,
                                          String path,
                                          boolean available) {

        /** 검증할 수 없는 중단 payload를 나타내는 안전한 대체값을 만든다. */
        private static ProbeInterruptedDetail unavailable() {
            return new ProbeInterruptedDetail(null, null, null, false);
        }
    }

    /**
     * Probe attempt 상세 JSON의 허용 필드만 보관한다.
     *
     * @param attemptNumber 시도 번호
     * @param statusCode HTTP 상태 코드
     * @param responseHash 응답 본문 해시
     * @param retryDecision 재시도 판정
     * @param failureCode 실패 코드
     * @param requestedAt 요청 시작 시각
     * @param respondedAt 응답 완료 시각
     * @param assertions assertion 판정 목록
     * @param available 상세 JSON을 안전하게 읽었으면 true
     * @param truncated assertion 상세가 크기 상한으로 생략됐으면 true
     */
    private record ProbeAttemptDetail(Integer attemptNumber, Integer statusCode,
                                      String responseHash, String retryDecision,
                                      String failureCode, String requestedAt, String respondedAt,
                                      List<Map<String, Object>> assertions,
                                      boolean available, boolean truncated) {

        /** 상세를 읽을 수 없는 안전한 대체 값을 만든다. */
        private static ProbeAttemptDetail unavailable() {
            return new ProbeAttemptDetail(null, null, null, null, null,
                    null, null, List.of(), false, false);
        }
    }

    /**
     * 요청과 응답 Evidence를 연결하는 내부 키다.
     *
     * @param stepId Probe Step 식별자
     * @param attemptNumber Step 내부 attempt 번호 문자열
     */
    private record ProbeAttemptKey(String stepId, String attemptNumber) {
    }
}
