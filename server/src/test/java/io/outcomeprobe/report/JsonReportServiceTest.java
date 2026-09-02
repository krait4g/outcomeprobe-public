package io.outcomeprobe.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * JsonReportService의 report 구조·요약 수치·실패 설명 생성을 검증한다.
 *
 * <p>검출 가능한 결함: summary 수치가 Evidence와 불일치, FAILED Run에 설명 누락,
 * PASSED Run에 불필요한 설명 포함, limitations 필드 누락, 과거 Step 진단 원문의
 * JSON·HTML 재노출.</p>
 */
class JsonReportServiceTest {

    private final RunEngine runEngine = mock(RunEngine.class);
    private final EvidenceQueryPort evidenceQueryPort = mock(EvidenceQueryPort.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonReportService service;

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T00:00:05Z");

    /**
     * 테스트 대상 서비스를 초기화한다.
     */
    @BeforeEach
    void setUp() {
        service = new JsonReportService(runEngine, evidenceQueryPort, objectMapper);
    }

    /**
     * 테스트용 Run fixture를 생성한다.
     */
    private ScenarioRun run(RunStatus status, String problemCode) {
        return new ScenarioRun("run-1", "payment-timeout", "1.0.0", "abc123",
                "vulnerable-consumer", 42L, status, 1, null,
                null, null, null, null, 1, 5, false,
                problemCode, null, START, END, START);
    }

    /**
     * 테스트용 Step fixture를 생성한다.
     */
    private RunStepExecution step(String stepId, int ordinal, StepStatus status) {
        return new RunStepExecution("s-" + ordinal, "run-1", stepId, ordinal,
                StepType.PROVIDER_EXPECT, status, null, null, null, null, null,
                1, null, status == StepStatus.FAILED ? "PROBE_ASSERTION_FAILED" : null,
                1, START, status.isTerminal() ? END : null);
    }

    /**
     * summary 집계만 검증할 때 사용할 최소 Evidence fixture를 만든다.
     *
     * @param sequence Run 내부 sequence
     * @param type 집계할 Evidence 유형
     * @return redaction 대상 원문이 없는 Evidence
     */
    private EvidenceEvent summaryEvidence(long sequence, EvidenceType type) {
        return new EvidenceEvent("summary-" + sequence, "run-1", sequence, null,
                START.plusMillis(sequence), type, EvidenceSource.ENGINE,
                null, null, null, null, Map.of(), null, null, false,
                START.plusMillis(sequence));
    }

    /**
     * Run이 없으면 empty를 반환한다.
     */
    @Test
    @DisplayName("존재하지 않는 Run은 empty를 반환한다")
    void missingRunReturnsEmpty() {
        when(runEngine.findRun("no-such")).thenReturn(Optional.empty());

        assertThat(service.buildReport("no-such")).isEmpty();
    }

    /**
     * Bundle overload는 전달된 snapshot만 사용하고 live 조회 포트를 호출하지 않는다.
     */
    @Test
    @DisplayName("Bundle JSON report는 고정 snapshot만 사용해 summary를 계산한다")
    @SuppressWarnings("unchecked")
    void snapshotReportDoesNotQueryLivePorts() {
        List<EvidenceEvent> timeline = List.of(
                summaryEvidence(1L, EvidenceType.PROVIDER_OPERATION_COMMITTED),
                summaryEvidence(2L, EvidenceType.CALLBACK_DELIVERY_SUCCEEDED),
                summaryEvidence(3L, EvidenceType.PROBE_ATTEMPTED));
        RunBundleSnapshot snapshot = new RunBundleSnapshot(
                run(RunStatus.PASSED, null),
                List.of(step("assert-final", 0, StepStatus.PASSED)), timeline);

        Map<String, Object> report = service.buildReport(snapshot);

        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(report.get("status")).isEqualTo("PASSED");
        assertThat(summary)
                .containsEntry("stepsPassed", 1L)
                .containsEntry("providerCommits", 1L)
                .containsEntry("callbackDeliveries", 1L)
                .containsEntry("callbackOmitted", 0L)
                .containsEntry("probeAttempts", 1L);
        verifyNoInteractions(runEngine, evidenceQueryPort);
    }

    /**
     * FAILED Run의 summary 수치가 Evidence count와 일치한다.
     */
    @Nested
    @DisplayName("summary 수치 정확성")
    class SummaryAccuracy {

        /**
         * providerCommits·callbackDeliveries·probeAttempts가 Evidence count에서 온다.
         */
        @Test
        @DisplayName("Evidence count가 summary에 정확히 반영된다")
        @SuppressWarnings("unchecked")
        void evidenceCountsReflected() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("expect-confirm", 0, StepStatus.PASSED),
                    step("assert-final", 1, StepStatus.FAILED)));
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(
                    summaryEvidence(1L, EvidenceType.PROVIDER_OPERATION_COMMITTED),
                    summaryEvidence(2L, EvidenceType.CALLBACK_OMITTED_BY_SCENARIO),
                    summaryEvidence(3L, EvidenceType.PROBE_ATTEMPTED),
                    summaryEvidence(4L, EvidenceType.PROBE_ATTEMPTED),
                    summaryEvidence(5L, EvidenceType.PROBE_ATTEMPTED),
                    summaryEvidence(6L, EvidenceType.PROBE_ATTEMPTED)));

            Optional<Map<String, Object>> report = service.buildReport("run-1");

            assertThat(report).isPresent();
            Map<String, Object> summary = (Map<String, Object>) report.get().get("summary");
            assertThat(summary.get("stepsPassed")).isEqualTo(1L);
            assertThat(summary.get("stepsFailed")).isEqualTo(1L);
            assertThat(summary.get("providerCommits")).isEqualTo(1L);
            assertThat(summary.get("callbackDeliveries")).isEqualTo(0L);
            assertThat(summary.get("callbackOmitted")).isEqualTo(1L);
            assertThat(summary.get("probeAttempts")).isEqualTo(4L);
        }
    }

    /**
     * 실패 설명 생성 규칙을 검증한다.
     */
    @Nested
    @DisplayName("explanation 생성")
    class Explanation {

        /**
         * FAILED + commit>0 + probe>0이면 수렴 실패 설명이 생성된다.
         */
        @Test
        @DisplayName("commit+probe FAILED는 수렴 실패 설명을 포함한다")
        @SuppressWarnings("unchecked")
        void failedWithCommitAndProbe() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(step("assert", 0, StepStatus.FAILED)));
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(
                    summaryEvidence(1L, EvidenceType.PROVIDER_OPERATION_COMMITTED),
                    summaryEvidence(2L, EvidenceType.PROBE_ATTEMPTED),
                    summaryEvidence(3L, EvidenceType.PROBE_ATTEMPTED),
                    summaryEvidence(4L, EvidenceType.PROBE_ATTEMPTED)));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();

            assertThat(report.get("explanation")).isNotNull();
            String explanation = (String) report.get("explanation");
            assertThat(explanation).contains("Provider는 성공으로 commit");
        }

        /**
         * PASSED Run에는 explanation이 없다.
         */
        @Test
        @DisplayName("PASSED Run은 explanation이 없다")
        @SuppressWarnings("unchecked")
        void passedHasNoExplanation() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.PASSED, null)));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(step("assert", 0, StepStatus.PASSED)));
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(
                    summaryEvidence(1L, EvidenceType.PROVIDER_OPERATION_COMMITTED),
                    summaryEvidence(2L, EvidenceType.CALLBACK_DELIVERY_SUCCEEDED),
                    summaryEvidence(3L, EvidenceType.PROBE_ATTEMPTED),
                    summaryEvidence(4L, EvidenceType.PROBE_ATTEMPTED)));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();

            assertThat(report.containsKey("explanation")).isFalse();
        }
    }

    /**
     * report 기본 구조를 검증한다.
     */
    @Nested
    @DisplayName("report 구조")
    class Structure {

        /**
         * 필수 필드가 모두 존재한다.
         */
        @Test
        @DisplayName("필수 필드(runId, scenario, status, limitations)가 존재한다")
        @SuppressWarnings("unchecked")
        void requiredFieldsPresent() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.PASSED, null)));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of());

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();

            assertThat(report.get("runId")).isEqualTo("run-1");
            assertThat(((Map<String, Object>) report.get("scenario")).get("name")).isEqualTo("payment-timeout");
            assertThat(report.get("status")).isEqualTo("PASSED");
            assertThat((List<String>) report.get("limitations")).isNotEmpty();
            assertThat(report.get("durationMs")).isEqualTo(5000L);
        }

        /**
         * FAILED Run의 failures 목록에 STEP_FAILED Evidence가 포함된다.
         */
        @Test
        @DisplayName("failures 목록에 실패 Evidence가 포함된다")
        @SuppressWarnings("unchecked")
        void failuresListPopulated() {
            EvidenceEvent failEvent = new EvidenceEvent("e-1", "run-1", 5, null,
                    Instant.now(), EvidenceType.STEP_FAILED, EvidenceSource.ENGINE,
                    "assert-final", null, null, null,
                    Map.of("problemCode", "PROBE_ASSERTION_FAILED"), null, null, false, Instant.now());
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_ASSERTION_FAILED")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(step("assert-final", 0, StepStatus.FAILED)));
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(failEvent));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> failures = (List<Map<String, Object>>) report.get("failures");

            assertThat(failures).hasSize(1);
            assertThat(failures.get(0).get("type")).isEqualTo("STEP_FAILED");
            assertThat(failures.get(0).get("stepId")).isEqualTo("assert-final");
        }
    }

    /**
     * 수렴 시간과 Probe 상세 근거가 report에 보존되는지 검증한다.
     */
    @Nested
    @DisplayName("수렴 및 Probe 근거")
    class ProbeEvidence {

        /**
         * 안정화가 끝난 최종 Probe 응답 관측 시각으로 수렴 시간을 계산한다.
         */
        @Test
        @DisplayName("Target trigger부터 안정화 Probe 응답까지 수렴 시간을 계산한다")
        void convergenceUsesStableProbeResponseTime() {
            EvidenceEvent trigger = evidence("e-1", 1, START.plusMillis(100),
                    EvidenceType.TARGET_REQUEST_SENT, "trigger", Map.of(), null, null);
            EvidenceEvent intermediate = evidence("e-2", 2, START.plusMillis(900),
                    EvidenceType.PROBE_ASSERTION_PENDING, "probe",
                    Map.of("attemptNumber", "1"), null, null);
            EvidenceEvent unrelatedStep = evidence("e-3", 3, START.plusMillis(1_000),
                    EvidenceType.STEP_PASSED, "wait", Map.of("stepType", "RUN_WAIT"), null, null);
            EvidenceEvent converged = evidence("e-4", 4, START.plusMillis(2_100),
                    EvidenceType.PROBE_ASSERTION_PASSED, "probe",
                    Map.of("attemptNumber", "2"), null, null);
            EvidenceEvent stepPassed = evidence("e-5", 5, START.plusMillis(2_150),
                    EvidenceType.STEP_PASSED, "probe",
                    Map.of("stepType", "PROBE_EVENTUALLY"), null, null);
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.PASSED, null)));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1"))
                    .thenReturn(List.of(trigger, intermediate, unrelatedStep, converged, stepPassed));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();

            assertThat(report.get("convergenceDurationMs")).isEqualTo(2_000L);
        }

        /**
         * Probe attempt의 상태, body hash, assertion과 retry 판정을 구조화해 노출한다.
         */
        @Test
        @DisplayName("Probe attempt와 assertion expected/actual을 report에 포함한다")
        @SuppressWarnings("unchecked")
        void probeAttemptDetailsAreReported() {
            String responseHash = "a".repeat(64);
            String detail = """
                    {
                      "attemptNumber": 3,
                      "requestedAt": "2026-07-26T00:00:00.500Z",
                      "respondedAt": "2026-07-26T00:00:00.575Z",
                      "statusCode": 200,
                      "responseHash": "%s",
                      "assertions": [{
                        "jsonPath": "$.status",
                        "operator": "EQUALS",
                        "expected": "SUCCEEDED",
                        "actual": "PROCESSING",
                        "passed": false
                      }],
                      "retryDecision": "PENDING",
                      "failureCode": null
                    }
                    """.formatted(responseHash);
            EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "3", "path", "/payments/p-1"),
                    "{\"attemptNumber\":3,\"path\":\"/payments/p-1\"}", null);
            EvidenceEvent response = evidence("e-2", 2, START.plusMillis(575),
                    EvidenceType.PROBE_RESPONSE_RECEIVED, "probe-final",
                    Map.of("attemptNumber", "3", "status", "200", "retryDecision", "PENDING",
                            "path", "/payments/p-1"), detail, responseHash);
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(request, response));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<Map<String, Object>> assertions =
                    (List<Map<String, Object>>) report.get("assertions");

            assertThat(attempts).hasSize(1);
            assertThat(attempts.get(0))
                    .containsEntry("attemptNumber", 3)
                    .containsEntry("status", "200")
                    .containsEntry("statusCode", 200)
                    .containsEntry("bodyHash", responseHash)
                    .containsEntry("retryDecision", "PENDING")
                    .containsEntry("requestedAt", START.plusMillis(500).toString())
                    .containsEntry("respondedAt", START.plusMillis(575).toString())
                    .containsEntry("detailsAvailable", true);
            assertThat(assertions).singleElement().satisfies(assertion -> assertThat(assertion)
                    .containsEntry("stepId", "probe-final")
                    .containsEntry("attemptNumber", 3)
                    .containsEntry("expected", "SUCCEEDED")
                    .containsEntry("actual", "PROCESSING")
                    .containsEntry("passed", false));
        }

        /** 응답이나 중단 결과가 없는 새 요청 의도는 정상 PLANNED 상태로 보고한다. */
        @Test
        @DisplayName("미완료 Probe 요청은 PLANNED 상태이며 손상 경고를 만들지 않는다")
        @SuppressWarnings("unchecked")
        void plannedProbeAttemptIsReportedWithoutIncompleteLimitation() {
            String requestedAt = START.plusMillis(500).toString();
            String payload = """
                    {
                      "attemptNumber": 2,
                      "path": "/payments/p-1",
                      "requestedAt": "%s",
                      "transmissionState": "PLANNED"
                    }
                    """.formatted(requestedAt);
            EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", requestedAt,
                            "transmissionState", "PLANNED"),
                    payload, null);
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(request));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<String> limitations = (List<String>) report.get("limitations");

            assertThat(attempts).singleElement().satisfies(attempt -> {
                assertThat(attempt)
                        .containsEntry("sequence", 1L)
                        .containsEntry("requestSequence", 1L)
                        .containsEntry("attemptNumber", 2)
                        .containsEntry("requestedAt", requestedAt)
                        .containsEntry("transmissionState", "PLANNED")
                        .containsEntry("path", "/payments/p-1")
                        .containsEntry("detailsAvailable", true)
                        .containsEntry("detailsTruncated", false);
                assertThat(attempt.get("respondedAt")).isNull();
                assertThat(attempt.get("observedAt")).isNull();
                assertThat(attempt.get("interruptedAt")).isNull();
                assertThat(attempt.get("status")).isNull();
                assertThat(attempt.get("statusCode")).isNull();
                assertThat(attempt.get("bodyHash")).isNull();
                assertThat(attempt.get("bodyHashUnavailableReason")).isNull();
                assertThat(attempt.get("retryDecision")).isNull();
                assertThat(attempt.get("failureCode")).isNull();
                assertThat(attempt.get("assertions")).isEqualTo(List.of());
            });
            assertThat(limitations)
                    .noneMatch(text -> text.contains("상세 JSON이 없거나 손상"));
        }

        /** malformed 또는 허용 목록 밖 PLANNED payload는 원문을 노출하지 않는다. */
        @Test
        @DisplayName("손상되거나 extra field가 있는 PLANNED 요청은 fail-closed 처리한다")
        @SuppressWarnings("unchecked")
        void malformedOrExtendedPlannedProbeAttemptIsFailClosed() {
            String requestedAt = START.plusMillis(500).toString();
            List<String> unsafePayloads = List.of(
                    "{SUPER_SECRET_MALFORMED",
                    """
                            {
                              "attemptNumber": 2,
                              "path": "/payments/p-1",
                              "requestedAt": "%s",
                              "transmissionState": "PLANNED",
                              "respondedAt": "SUPER_SECRET_EXTRA"
                            }
                            """.formatted(requestedAt));
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());

            for (String unsafePayload : unsafePayloads) {
                EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                        EvidenceType.PROBE_ATTEMPTED, "probe-final",
                        Map.of("attemptNumber", "2", "path", "/payments/p-1",
                                "requestedAt", requestedAt,
                                "transmissionState", "PLANNED"),
                        unsafePayload, null);
                when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(request));

                Map<String, Object> report = service.buildReport("run-1").orElseThrow();
                List<Map<String, Object>> attempts =
                        (List<Map<String, Object>>) report.get("probeAttempts");
                List<String> limitations = (List<String>) report.get("limitations");

                assertThat(report.toString())
                        .doesNotContain("SUPER_SECRET_MALFORMED", "SUPER_SECRET_EXTRA");
                assertThat(attempts).singleElement().satisfies(attempt -> {
                    assertThat(attempt)
                            .containsEntry("requestedAt", requestedAt)
                            .containsEntry("transmissionState", "PLANNED")
                            .containsEntry("detailsAvailable", false)
                            .containsEntry("detailsTruncated", false);
                    assertThat(attempt.get("respondedAt")).isNull();
                    assertThat(attempt.get("observedAt")).isNull();
                    assertThat(attempt.get("interruptedAt")).isNull();
                    assertThat(attempt.get("status")).isNull();
                    assertThat(attempt.get("statusCode")).isNull();
                    assertThat(attempt.get("bodyHash")).isNull();
                    assertThat(attempt.get("retryDecision")).isNull();
                    assertThat(attempt.get("failureCode")).isNull();
                    assertThat(attempt.get("path")).isNull();
                });
                assertThat(limitations)
                        .anyMatch(text -> text.contains("상세 JSON이 없거나 손상"));
            }
        }

        /** 요청 payload 시각이 Evidence 관측 시각과 다르면 관측 시각을 권위값으로 보존한다. */
        @Test
        @DisplayName("PLANNED 요청의 requestedAt 불일치는 fail-closed 처리한다")
        @SuppressWarnings("unchecked")
        void plannedRequestedAtMismatchIsFailClosed() {
            String authoritativeRequestedAt = START.plusMillis(500).toString();
            String mismatchedRequestedAt = START.plusMillis(600).toString();
            String payload = """
                    {
                      "attemptNumber": 2,
                      "path": "/payments/p-1",
                      "requestedAt": "%s",
                      "transmissionState": "PLANNED"
                    }
                    """.formatted(mismatchedRequestedAt);
            EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", mismatchedRequestedAt,
                            "transmissionState", "PLANNED"),
                    payload, null);
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(request));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<String> limitations = (List<String>) report.get("limitations");

            assertThat(attempts).singleElement().satisfies(attempt -> {
                assertThat(attempt)
                        .containsEntry("requestedAt", authoritativeRequestedAt)
                        .containsEntry("transmissionState", "PLANNED")
                        .containsEntry("detailsAvailable", false);
                assertThat(attempt.get("respondedAt")).isNull();
                assertThat(attempt.get("observedAt")).isNull();
                assertThat(attempt.get("interruptedAt")).isNull();
                assertThat(attempt.get("path")).isNull();
            });
            assertThat(limitations)
                    .anyMatch(text -> text.contains("상세 JSON이 없거나 손상"));
        }

        /** 결과 commit 전 중단된 요청은 응답값 없이 별도 상태로 보고한다. */
        @Test
        @DisplayName("중단된 Probe 요청은 UNKNOWN 전송 상태와 null 응답 필드로 보고한다")
        @SuppressWarnings("unchecked")
        void interruptedProbeAttemptIsReportedWithoutInventedResponseFacts() {
            String requestedAt = START.plusMillis(500).toString();
            String requestPayload = """
                    {
                      "attemptNumber": 2,
                      "path": "/payments/p-1",
                      "requestedAt": "%s",
                      "transmissionState": "PLANNED"
                    }
                    """.formatted(requestedAt);
            String interruptionPayload = """
                    {
                      "attemptNumber": 2,
                      "path": "/payments/p-1",
                      "requestedAt": "%s",
                      "transmissionState": "UNKNOWN",
                      "retryDecision": "PENDING",
                      "failureCode": "PROBE_RESULT_NOT_COMMITTED",
                      "bodyHashUnavailableReason": "RESULT_NOT_COMMITTED"
                    }
                    """.formatted(requestedAt);
            EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", requestedAt,
                            "transmissionState", "PLANNED"),
                    requestPayload, null);
            EvidenceEvent interruption = evidence("e-2", 2, START.plusSeconds(2),
                    EvidenceType.PROBE_ATTEMPT_INTERRUPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", requestedAt,
                            "transmissionState", "UNKNOWN",
                            "retryDecision", "PENDING",
                            "failureCode", "PROBE_RESULT_NOT_COMMITTED",
                            "bodyHashUnavailableReason", "RESULT_NOT_COMMITTED"),
                    interruptionPayload, null);
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1"))
                    .thenReturn(List.of(request, interruption));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<String> limitations = (List<String>) report.get("limitations");

            assertThat(attempts).singleElement().satisfies(attempt -> {
                assertThat(attempt)
                        .containsEntry("sequence", 2L)
                        .containsEntry("requestSequence", 1L)
                        .containsEntry("attemptNumber", 2)
                        .containsEntry("requestedAt", requestedAt)
                        .containsEntry("interruptedAt", START.plusSeconds(2).toString())
                        .containsEntry("transmissionState", "UNKNOWN")
                        .containsEntry("retryDecision", "PENDING")
                        .containsEntry("failureCode", "PROBE_RESULT_NOT_COMMITTED")
                        .containsEntry("bodyHashUnavailableReason", "RESULT_NOT_COMMITTED")
                        .containsEntry("path", "/payments/p-1")
                        .containsEntry("detailsAvailable", true)
                        .containsEntry("detailsTruncated", false);
                assertThat(attempt.get("respondedAt")).isNull();
                assertThat(attempt.get("observedAt")).isNull();
                assertThat(attempt.get("status")).isNull();
                assertThat(attempt.get("statusCode")).isNull();
                assertThat(attempt.get("bodyHash")).isNull();
                assertThat(attempt.get("assertions")).isEqualTo(List.of());
            });
            assertThat(limitations)
                    .anyMatch(text -> text.contains("결과가 확정되지 않아 복구 처리")
                            && text.contains("transmissionState")
                            && text.contains("UNKNOWN"))
                    .noneMatch(text -> text.contains("상세 JSON이 없거나 손상"));
        }

        /** 요청 Evidence와 중단 payload의 요청 시각이 다르면 요청 Evidence를 권위값으로 삼는다. */
        @Test
        @DisplayName("중단 payload의 requestedAt 불일치는 fail-closed로 처리한다")
        @SuppressWarnings("unchecked")
        void interruptedRequestedAtMismatchIsFailClosed() {
            String authoritativeRequestedAt = START.plusMillis(500).toString();
            String mismatchedRequestedAt = START.plusMillis(600).toString();
            EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", authoritativeRequestedAt,
                            "transmissionState", "PLANNED"),
                    "{\"attemptNumber\":2,\"transmissionState\":\"PLANNED\"}", null);
            String interruptionPayload = """
                    {
                      "attemptNumber": 2,
                      "path": "/payments/p-1",
                      "requestedAt": "%s",
                      "transmissionState": "UNKNOWN",
                      "retryDecision": "PENDING",
                      "failureCode": "PROBE_RESULT_NOT_COMMITTED",
                      "bodyHashUnavailableReason": "RESULT_NOT_COMMITTED"
                    }
                    """.formatted(mismatchedRequestedAt);
            EvidenceEvent interruption = evidence("e-2", 2, START.plusSeconds(2),
                    EvidenceType.PROBE_ATTEMPT_INTERRUPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", mismatchedRequestedAt,
                            "transmissionState", "UNKNOWN",
                            "retryDecision", "PENDING",
                            "failureCode", "PROBE_RESULT_NOT_COMMITTED",
                            "bodyHashUnavailableReason", "RESULT_NOT_COMMITTED"),
                    interruptionPayload, null);
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1"))
                    .thenReturn(List.of(request, interruption));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<String> limitations = (List<String>) report.get("limitations");

            assertThat(attempts).singleElement().satisfies(attempt -> {
                assertThat(attempt)
                        .containsEntry("requestedAt", authoritativeRequestedAt)
                        .containsEntry("interruptedAt", START.plusSeconds(2).toString())
                        .containsEntry("detailsAvailable", false);
                assertThat(attempt.get("respondedAt")).isNull();
                assertThat(attempt.get("observedAt")).isNull();
                assertThat(attempt.get("path")).isNull();
            });
            assertThat(limitations)
                    .anyMatch(text -> text.contains("상세 JSON이 없거나 손상"));
        }

        /** 손상된 중단 payload는 원문을 노출하지 않고 일반 불완전 경고도 남긴다. */
        @Test
        @DisplayName("손상된 Probe 중단 상세는 fail-closed로 보고한다")
        @SuppressWarnings("unchecked")
        void malformedInterruptedProbeAttemptIsFailClosed() {
            String requestedAt = START.plusMillis(500).toString();
            EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", requestedAt,
                            "transmissionState", "PLANNED"),
                    "{\"attemptNumber\":2,\"transmissionState\":\"PLANNED\"}", null);
            String unsafePayload = """
                    {
                      "attemptNumber": 2,
                      "path": "/payments/p-1",
                      "requestedAt": "%s",
                      "transmissionState": "UNKNOWN",
                      "retryDecision": "PENDING",
                      "failureCode": "PROBE_RESULT_NOT_COMMITTED",
                      "bodyHashUnavailableReason": "RESULT_NOT_COMMITTED",
                      "respondedAt": "SUPER_SECRET_RESPONSE"
                    }
                    """.formatted(requestedAt);
            EvidenceEvent interruption = evidence("e-2", 2, START.plusSeconds(2),
                    EvidenceType.PROBE_ATTEMPT_INTERRUPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", requestedAt,
                            "transmissionState", "UNKNOWN",
                            "retryDecision", "PENDING",
                            "failureCode", "PROBE_RESULT_NOT_COMMITTED",
                            "bodyHashUnavailableReason", "RESULT_NOT_COMMITTED"),
                    unsafePayload, null);
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1"))
                    .thenReturn(List.of(request, interruption));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<String> limitations = (List<String>) report.get("limitations");

            assertThat(report.toString()).doesNotContain("SUPER_SECRET_RESPONSE");
            assertThat(attempts).singleElement().satisfies(attempt -> {
                assertThat(attempt)
                        .containsEntry("detailsAvailable", false)
                        .containsEntry("interruptedAt", START.plusSeconds(2).toString())
                        .containsEntry("transmissionState", "UNKNOWN")
                        .containsEntry("failureCode", "PROBE_RESULT_NOT_COMMITTED")
                        .containsEntry("bodyHashUnavailableReason", "RESULT_NOT_COMMITTED");
                assertThat(attempt.get("respondedAt")).isNull();
                assertThat(attempt.get("observedAt")).isNull();
                assertThat(attempt.get("status")).isNull();
                assertThat(attempt.get("statusCode")).isNull();
                assertThat(attempt.get("bodyHash")).isNull();
                assertThat(attempt.get("path")).isNull();
            });
            assertThat(limitations)
                    .anyMatch(text -> text.contains("결과가 확정되지 않아 복구 처리")
                            && text.contains("transmissionState")
                            && text.contains("UNKNOWN"))
                    .anyMatch(text -> text.contains("상세 JSON이 없거나 손상"));
        }

        /** 같은 attempt의 응답이 commit됐다면 뒤늦은 중단 표식보다 응답을 우선한다. */
        @Test
        @DisplayName("응답과 중단 Evidence가 함께 있으면 응답 결과를 한 번만 보고한다")
        @SuppressWarnings("unchecked")
        void committedResponseTakesPrecedenceOverInterruption() {
            String requestedAt = START.plusMillis(500).toString();
            String responseHash = "a".repeat(64);
            String responsePayload = """
                    {
                      "attemptNumber": 2,
                      "requestedAt": "%s",
                      "respondedAt": "%s",
                      "statusCode": 200,
                      "responseHash": "%s",
                      "assertions": [],
                      "retryDecision": "CONVERGED",
                      "failureCode": null
                    }
                    """.formatted(requestedAt, START.plusMillis(700), responseHash);
            String interruptionPayload = """
                    {
                      "attemptNumber": 2,
                      "path": "/payments/p-1",
                      "requestedAt": "%s",
                      "transmissionState": "UNKNOWN",
                      "retryDecision": "PENDING",
                      "failureCode": "PROBE_RESULT_NOT_COMMITTED",
                      "bodyHashUnavailableReason": "RESULT_NOT_COMMITTED"
                    }
                    """.formatted(requestedAt);
            EvidenceEvent request = evidence("e-1", 1, START.plusMillis(500),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1"),
                    "{\"attemptNumber\":2}", null);
            EvidenceEvent response = evidence("e-2", 2, START.plusMillis(700),
                    EvidenceType.PROBE_RESPONSE_RECEIVED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "status", "200", "retryDecision", "CONVERGED"),
                    responsePayload, responseHash);
            EvidenceEvent interruption = evidence("e-3", 3, START.plusSeconds(2),
                    EvidenceType.PROBE_ATTEMPT_INTERRUPTED, "probe-final",
                    Map.of("attemptNumber", "2", "path", "/payments/p-1",
                            "requestedAt", requestedAt,
                            "transmissionState", "UNKNOWN",
                            "retryDecision", "PENDING",
                            "failureCode", "PROBE_RESULT_NOT_COMMITTED",
                            "bodyHashUnavailableReason", "RESULT_NOT_COMMITTED"),
                    interruptionPayload, null);
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.PASSED, null)));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1"))
                    .thenReturn(List.of(request, response, interruption));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<String> limitations = (List<String>) report.get("limitations");

            assertThat(attempts).singleElement().satisfies(attempt -> assertThat(attempt)
                    .containsEntry("sequence", 2L)
                    .containsEntry("statusCode", 200)
                    .containsEntry("bodyHash", responseHash)
                    .containsEntry("retryDecision", "CONVERGED"));
            assertThat(limitations)
                    .noneMatch(text -> text.contains("transmissionState")
                            && text.contains("UNKNOWN"));
        }

        /**
         * 과거 Step output의 진단 원문은 신뢰하지 않고 JSON과 HTML에서 함께 생략한다.
         *
         * @throws Exception report JSON 직렬화가 실패하면 테스트를 실패시킨다
         */
        @Test
        @DisplayName("legacy Step assertion 진단값은 JSON과 HTML에서 구조적으로 숨긴다")
        @SuppressWarnings("unchecked")
        void legacyStepAssertionDiagnosticsAreOmittedFromJsonAndHtml() throws Exception {
            String expectedSecret = "legacy-expected-secret";
            String actualSecret = "legacy-actual-secret";
            ObjectNode output = objectMapper.createObjectNode();
            ObjectNode legacyAssertion = output.putArray("assertions").addObject();
            legacyAssertion.put("jsonPath", "$.credential.token");
            legacyAssertion.put("operator", "EQUALS");
            legacyAssertion.put("expected", expectedSecret);
            legacyAssertion.putObject("actual").put("token", actualSecret);
            legacyAssertion.put("passed", false);

            RunStepExecution legacyStep = new RunStepExecution(
                    "legacy-step-row", "run-1", "probe-final", 0,
                    StepType.PROBE_EVENTUALLY, StepStatus.FAILED,
                    null, output, null, null, null, 7, null,
                    "PROBE_ASSERTION_FAILED", 1, START, END);
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_ASSERTION_FAILED")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(legacyStep));
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of());

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> assertions =
                    (List<Map<String, Object>>) report.get("assertions");
            Map<String, Object> assertion = assertions.get(0);
            String json = objectMapper.writeValueAsString(report);
            String html = new HtmlReportService(service, objectMapper).render(report);

            assertThat(assertions).hasSize(1);
            assertThat(assertion)
                    .containsEntry("stepId", "probe-final")
                    .containsEntry("attemptNumber", 7)
                    .containsEntry("jsonPath", "$.credential.token")
                    .containsEntry("operator", "EQUALS")
                    .containsEntry("passed", false)
                    .containsEntry("diagnosticsAvailable", false)
                    .containsEntry("diagnosticsUnavailableReason",
                            "LEGACY_STEP_OUTPUT_REDACTION_UNVERIFIED")
                    .containsKeys("expected", "actual");
            assertThat(assertion.get("expected")).isNull();
            assertThat(assertion.get("actual")).isNull();
            assertThat(json)
                    .doesNotContain(expectedSecret, actualSecret)
                    .contains("LEGACY_STEP_OUTPUT_REDACTION_UNVERIFIED");
            assertThat(html)
                    .doesNotContain(expectedSecret, actualSecret)
                    .contains("LEGACY_STEP_OUTPUT_REDACTION_UNVERIFIED");
        }

        /**
         * 손상된 Probe JSON 원문을 재노출하지 않고 제한 사항만 남긴다.
         */
        @Test
        @DisplayName("손상된 Probe 상세 원문은 report에 노출하지 않는다")
        @SuppressWarnings("unchecked")
        void malformedProbeSummaryIsFailClosed() {
            EvidenceEvent attempt = evidence("e-1", 1, START,
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "1", "status", "503"),
                    "{SUPER_SECRET_RAW", "sha256:redacted-source");
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(attempt));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");
            List<String> limitations = (List<String>) report.get("limitations");

            assertThat(report.toString()).doesNotContain("SUPER_SECRET_RAW");
            assertThat(attempts.get(0))
                    .containsEntry("detailsAvailable", false)
                    .containsEntry("respondedAt", null);
            assertThat(limitations).anyMatch(text -> text.contains("Probe attempt 상세 JSON"));
        }

        /** legacy 단일 Evidence는 payload의 두 시각을 하나의 event 시각으로 왜곡하지 않는다. */
        @Test
        @DisplayName("legacy Probe 상세의 요청·응답 시각을 보존한다")
        @SuppressWarnings("unchecked")
        void legacyAttemptUsesPayloadTimestamps() {
            String detail = """
                    {
                      "attemptNumber": 1,
                      "requestedAt": "2026-07-26T00:00:00.100Z",
                      "respondedAt": "2026-07-26T00:00:00.250Z",
                      "statusCode": 200,
                      "responseHash": "%s",
                      "assertions": [],
                      "retryDecision": "PENDING",
                      "failureCode": null
                    }
                    """.formatted("a".repeat(64));
            EvidenceEvent legacy = evidence("e-1", 1, START.plusSeconds(9),
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "1", "status", "200"), detail,
                    "a".repeat(64));
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(legacy));

            List<Map<String, Object>> attempts = (List<Map<String, Object>>)
                    service.buildReport("run-1").orElseThrow().get("probeAttempts");

            assertThat(attempts).singleElement().satisfies(attempt -> assertThat(attempt)
                    .containsEntry("requestedAt", "2026-07-26T00:00:00.100Z")
                    .containsEntry("respondedAt", "2026-07-26T00:00:00.250Z"));
        }

        /** JSON 문법이 맞아도 생산자 타입 계약 밖의 객체 진단값은 재노출하지 않는다. */
        @Test
        @DisplayName("유효 JSON 형태의 비계약 Probe 상세도 fail-closed 처리한다")
        @SuppressWarnings("unchecked")
        void validJsonOutsideProducerContractIsFailClosed() {
            String unsafe = """
                    {
                      "attemptNumber": 1,
                      "statusCode": 200,
                      "responseHash": "%s",
                      "assertions": [{
                        "jsonPath": "$.token",
                        "operator": "EQUALS",
                        "expected": {"secret":"must-not-leak"},
                        "actual": "safe",
                        "passed": false
                      }],
                      "retryDecision": "PENDING",
                      "failureCode": null
                    }
                    """.formatted("a".repeat(64));
            EvidenceEvent request = evidence("e-1", 1, START,
                    EvidenceType.PROBE_ATTEMPTED, "probe-final",
                    Map.of("attemptNumber", "1", "path", "/state"),
                    "{\"attemptNumber\":1}", null);
            EvidenceEvent response = evidence("e-2", 2, START.plusMillis(10),
                    EvidenceType.PROBE_RESPONSE_RECEIVED, "probe-final",
                    Map.of("attemptNumber", "1", "status", "200"), unsafe,
                    "a".repeat(64));
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());
            when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(request, response));

            Map<String, Object> report = service.buildReport("run-1").orElseThrow();
            List<Map<String, Object>> attempts =
                    (List<Map<String, Object>>) report.get("probeAttempts");

            assertThat(report.toString()).doesNotContain("must-not-leak");
            assertThat(attempts).singleElement().satisfies(attempt ->
                    assertThat(attempt).containsEntry("detailsAvailable", false));
        }

        /**
         * report 테스트용 Evidence fixture를 만든다.
         */
        private EvidenceEvent evidence(String id, long sequence, Instant observedAt,
                                       EvidenceType type, String stepId,
                                       Map<String, String> attributes, String payloadSummary,
                                       String bodyHash) {
            return new EvidenceEvent(id, "run-1", sequence, null, observedAt, type,
                    EvidenceSource.PROBE, stepId, null, null, null, attributes,
                    payloadSummary, bodyHash, payloadSummary != null, observedAt);
        }
    }
}
