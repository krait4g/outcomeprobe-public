package io.outcomeprobe.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * JUnitXmlReportService의 XML 구조·매핑·이스케이프를 검증한다.
 *
 * <p>검출 가능한 결함: FAILED Step이 failure가 아닌 success로 보고, ERROR가
 * failure로 매핑, XML 특수 문자가 이스케이프 없이 삽입돼 파싱 실패.</p>
 */
class JUnitXmlReportServiceTest {

    private final RunEngine runEngine = mock(RunEngine.class);
    private JUnitXmlReportService service;

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T00:00:03Z");

    /**
     * 테스트 대상 서비스를 초기화한다.
     */
    @BeforeEach
    void setUp() {
        service = new JUnitXmlReportService(runEngine);
    }

    /**
     * 테스트용 Run fixture를 생성한다.
     */
    private ScenarioRun run(RunStatus status, String problemCode) {
        return new ScenarioRun("run-1", "payment-timeout", "1.0.0", "abc123",
                "vulnerable", 42L, status, 1, null,
                null, null, null, null, 1, 5, false,
                problemCode, null, START, END, START);
    }

    /**
     * 테스트용 Step fixture를 생성한다.
     */
    private RunStepExecution step(String stepId, int ordinal, StepStatus status, String problemCode) {
        return new RunStepExecution("s-" + ordinal, "run-1", stepId, ordinal,
                StepType.PROVIDER_EXPECT, status, null, null, null, null, null,
                1, null, problemCode, 1, START, status.isTerminal() ? END : null);
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
     * Bundle overload는 고정된 Run·Step만 렌더링하고 live RunEngine을 조회하지 않는다.
     */
    @Test
    @DisplayName("Bundle JUnit은 고정 snapshot의 최종 판정만 사용한다")
    void snapshotReportDoesNotQueryRunEngine() {
        RunBundleSnapshot snapshot = new RunBundleSnapshot(
                run(RunStatus.FAILED, "FINAL_EXPECTATION_FAILED"),
                List.of(step("action", 0, StepStatus.PASSED, null)),
                List.of());

        String xml = service.buildReport(snapshot);

        assertThat(xml)
                .contains("tests=\"2\"")
                .contains("failures=\"1\"")
                .contains("name=\"run-verdict\"")
                .contains("FINAL_EXPECTATION_FAILED");
        verifyNoInteractions(runEngine);
    }

    /**
     * Step 상태 → JUnit 요소 매핑을 검증한다.
     */
    @Nested
    @DisplayName("상태 매핑")
    class StatusMapping {

        /**
         * FAILED Step은 failure 요소로 매핑된다.
         */
        @Test
        @DisplayName("FAILED Step은 <failure>로 매핑된다")
        void failedMapsToFailure() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, "PROBE_TIMEOUT")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("expect-confirm", 0, StepStatus.PASSED, null),
                    step("assert-final", 1, StepStatus.FAILED, "PROBE_ASSERTION_FAILED")));

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("<failure message=\"PROBE_ASSERTION_FAILED\"");
            assertThat(xml).contains("failures=\"2\"");
            assertThat(xml).contains("name=\"run-verdict\"");
            assertThat(xml).doesNotContain("<error");
        }

        /**
         * ERROR Step은 error 요소로 매핑된다.
         */
        @Test
        @DisplayName("ERROR Step은 <error>로 매핑된다")
        void errorMapsToError() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.ERROR, "INTERNAL")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("broken-step", 0, StepStatus.ERROR, "INTERNAL_EXECUTION_ERROR")));

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("<error message=\"INTERNAL_EXECUTION_ERROR\"");
            assertThat(xml).contains("errors=\"2\"");
        }

        /**
         * SKIPPED Step은 skipped 요소로 매핑된다.
         */
        @Test
        @DisplayName("SKIPPED Step은 <skipped/>로 매핑된다")
        void skippedMapsToSkipped() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, "X")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("skipped-step", 0, StepStatus.SKIPPED, null)));

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("<skipped message=\"SKIPPED\"/>");
            assertThat(xml).contains("skipped=\"1\"");
        }

        /**
         * 모든 Step이 통과해도 FAILED Run의 최종 판정은 failure로 남는다.
         */
        @Test
        @DisplayName("FAILED Run은 Step 통과 여부와 무관하게 run-verdict failure다")
        void failedRunWithPassedStepsHasFailureVerdict() {
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.FAILED, "FINAL_EXPECTATION_FAILED")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("action", 0, StepStatus.PASSED, null),
                    step("probe", 1, StepStatus.PASSED, null)));

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("tests=\"3\"");
            assertThat(xml).contains("failures=\"1\"");
            assertThat(xml).contains("errors=\"0\"");
            assertThat(xml).contains("name=\"run-verdict\"");
            assertThat(xml).contains("<failure message=\"FINAL_EXPECTATION_FAILED\""
                    + " type=\"FINAL_EXPECTATION_FAILED\"");
        }

        /**
         * 취소된 Run은 CI 오류나 assertion 실패가 아니라 skipped로 표시된다.
         */
        @Test
        @DisplayName("CANCELLED Run의 run-verdict는 skipped다")
        void cancelledRunMapsToSkippedVerdict() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.CANCELLED, null)));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("action", 0, StepStatus.PASSED, null)));

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("tests=\"2\"");
            assertThat(xml).contains("failures=\"0\"");
            assertThat(xml).contains("errors=\"0\"");
            assertThat(xml).contains("skipped=\"1\"");
            assertThat(xml).contains("<skipped message=\"CANCELLED\"");
        }
    }

    /**
     * XML 구조와 이스케이프를 검증한다.
     */
    @Nested
    @DisplayName("XML 구조")
    class XmlStructure {

        /**
         * testsuite 속성(tests, time)이 정확하다.
         */
        @Test
        @DisplayName("testsuite 속성이 Step 수와 소요 시간을 반영한다")
        void suiteAttributes() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.PASSED, null)));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("step-a", 0, StepStatus.PASSED, null),
                    step("step-b", 1, StepStatus.PASSED, null)));

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("tests=\"3\"");
            assertThat(xml).contains("time=\"3.000\"");
            assertThat(xml).startsWith("<?xml version=\"1.0\"");
        }

        /**
         * XML 특수 문자가 이스케이프된다.
         */
        @Test
        @DisplayName("특수 문자(<, >, &)가 이스케이프된다")
        void specialCharsEscaped() {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run(RunStatus.FAILED, null)));
            when(runEngine.findSteps("run-1")).thenReturn(List.of(
                    step("step<with>&\"chars", 0, StepStatus.FAILED, "CODE<X>")));

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("step&lt;with&gt;&amp;&quot;chars");
            assertThat(xml).doesNotContain("step<with>");
        }

        /**
         * Run ERROR + Step 실패 0이면 suite 수준 error testcase가 추가된다.
         */
        @Test
        @DisplayName("Run ERROR + Step 실패 0이면 run-verdict error가 추가된다")
        void runErrorWithoutStepError() {
            when(runEngine.findRun("run-1"))
                    .thenReturn(Optional.of(run(RunStatus.ERROR, "INTERNAL_EXECUTION_ERROR")));
            when(runEngine.findSteps("run-1")).thenReturn(List.of());

            String xml = service.buildReport("run-1").orElseThrow();

            assertThat(xml).contains("name=\"run-verdict\"");
            assertThat(xml).contains("INTERNAL_EXECUTION_ERROR");
        }
    }
}
