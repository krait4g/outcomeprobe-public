package io.outcomeprobe.report;

import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Run 결과를 CI가 판정할 수 있는 JUnit XML로 변환한다.
 *
 * <p>책임: 각 Step과 Run 최종 판정을 testcase로 만들고 실제 testcase 목록에서
 * tests, failures, errors, skipped 집계를 계산한다.</p>
 * <p>핵심 불변식: Step이 모두 통과했더라도 Run이 FAILED면 run-verdict testcase가
 * 반드시 failure가 된다. OutcomeProbe 내부 오류는 error, 사용자 취소는 skipped로
 * 구분해 CI의 성공 결과로 잘못 표시되지 않게 한다.</p>
 */
@Service
public class JUnitXmlReportService {

    private final RunEngine runEngine;

    /**
     * Run과 Step 조회 엔진을 주입한다.
     *
     * @param runEngine 실행 엔진
     */
    public JUnitXmlReportService(RunEngine runEngine) {
        this.runEngine = runEngine;
    }

    /**
     * Run의 JUnit XML 문서를 생성한다.
     *
     * @param runId Run 식별자
     * @return XML 문자열, Run이 없으면 empty
     */
    public Optional<String> buildReport(String runId) {
        Optional<ScenarioRun> runOpt = runEngine.findRun(runId);
        if (runOpt.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRun run = runOpt.get();
        return Optional.of(render(run, runEngine.findSteps(runId)));
    }

    /**
     * 이미 고정된 Bundle snapshot만 사용해 JUnit XML을 생성한다.
     *
     * <p>이 overload는 RunEngine을 다시 조회하지 않는다. 따라서 최종 Run 판정과
     * Step testcase 집계가 report.json 및 timeline.json과 같은 읽기 시점을 반영한다.</p>
     *
     * @param snapshot 같은 읽기 트랜잭션에서 고정한 Bundle snapshot
     * @return JUnit XML 문자열
     */
    public String buildReport(RunBundleSnapshot snapshot) {
        return render(snapshot.run(), snapshot.steps());
    }

    /**
     * 조회가 끝난 Run과 Step만 사용해 JUnit XML을 순수 렌더링한다.
     *
     * @param run 고정된 Run snapshot
     * @param steps 고정된 Step snapshot
     * @return JUnit XML 문자열
     */
    private String render(ScenarioRun run, List<RunStepExecution> steps) {
        List<TestCaseResult> testCases = testCases(run, steps);

        long failures = testCases.stream()
                .filter(testCase -> testCase.kind() == ResultKind.FAILURE).count();
        long errors = testCases.stream()
                .filter(testCase -> testCase.kind() == ResultKind.ERROR).count();
        long skipped = testCases.stream()
                .filter(testCase -> testCase.kind() == ResultKind.SKIPPED).count();
        double seconds = run.startedAt() != null && run.finishedAt() != null
                ? Duration.between(run.startedAt(), run.finishedAt()).toMillis() / 1000.0
                : 0.0;

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuites>\n");
        xml.append("<testsuite name=\"").append(escape(run.scenarioName()))
                .append("@").append(escape(run.scenarioVersion())).append("\"")
                .append(" tests=\"").append(testCases.size()).append("\"")
                .append(" failures=\"").append(failures).append("\"")
                .append(" errors=\"").append(errors).append("\"")
                .append(" skipped=\"").append(skipped).append("\"")
                .append(" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", seconds))
                .append("\">\n");

        for (TestCaseResult testCase : testCases) {
            appendTestCase(xml, run.scenarioName(), testCase);
        }

        xml.append("</testsuite>\n</testsuites>\n");
        return xml.toString();
    }

    /**
     * Step 결과와 항상 존재하는 Run 최종 판정 testcase를 만든다.
     */
    private List<TestCaseResult> testCases(ScenarioRun run, List<RunStepExecution> steps) {
        List<TestCaseResult> testCases = new ArrayList<>();
        for (RunStepExecution step : steps) {
            testCases.add(stepTestCase(step));
        }
        testCases.add(runVerdict(run));
        return List.copyOf(testCases);
    }

    /**
     * Step 상태를 JUnit testcase 결과로 변환한다.
     */
    private TestCaseResult stepTestCase(RunStepExecution step) {
        return switch (step.status()) {
            case PASSED -> new TestCaseResult(step.stepId(), ResultKind.PASSED, null, null);
            case FAILED -> new TestCaseResult(step.stepId(), ResultKind.FAILURE,
                    failureMessage(step), problemType(step));
            case ERROR -> new TestCaseResult(step.stepId(), ResultKind.ERROR,
                    failureMessage(step), problemType(step));
            case PENDING, RUNNING, WAITING, SKIPPED, CANCELLED ->
                    new TestCaseResult(step.stepId(), ResultKind.SKIPPED,
                            step.status().name(), step.status().name());
        };
    }

    /**
     * Run 상태를 최종 판정 testcase로 변환한다.
     */
    private TestCaseResult runVerdict(ScenarioRun run) {
        String message = run.problemCode() == null ? run.status().name() : run.problemCode();
        return switch (run.status()) {
            case PASSED -> new TestCaseResult("run-verdict", ResultKind.PASSED, null, null);
            case FAILED -> new TestCaseResult("run-verdict", ResultKind.FAILURE,
                    message, run.problemCode() == null ? "RUN_FAILED" : run.problemCode());
            case ERROR -> new TestCaseResult("run-verdict", ResultKind.ERROR,
                    message, run.problemCode() == null
                            ? "INTERNAL_EXECUTION_ERROR" : run.problemCode());
            case CANCELLED -> new TestCaseResult("run-verdict", ResultKind.SKIPPED,
                    message, "RUN_CANCELLED");
            case QUEUED, PREPARING, RUNNING, ASSERTING ->
                    new TestCaseResult("run-verdict", ResultKind.SKIPPED,
                            "Run이 아직 최종 상태가 아닙니다: " + run.status().name(),
                            "RUN_NOT_TERMINAL");
        };
    }

    /**
     * testcase 하나를 XML에 추가한다.
     */
    private void appendTestCase(StringBuilder xml, String scenarioName, TestCaseResult testCase) {
        xml.append("  <testcase name=\"").append(escape(testCase.name())).append("\"")
                .append(" classname=\"").append(escape(scenarioName)).append("\"");
        switch (testCase.kind()) {
            case PASSED -> xml.append("/>\n");
            case FAILURE -> xml.append(">\n    <failure message=\"")
                    .append(escape(testCase.message())).append("\" type=\"")
                    .append(escape(testCase.type())).append("\"/>\n  </testcase>\n");
            case ERROR -> xml.append(">\n    <error message=\"")
                    .append(escape(testCase.message())).append("\" type=\"")
                    .append(escape(testCase.type())).append("\"/>\n  </testcase>\n");
            case SKIPPED -> xml.append(">\n    <skipped message=\"")
                    .append(escape(testCase.message())).append("\"/>\n  </testcase>\n");
        }
    }

    /**
     * Step 실패 메시지를 조립한다.
     */
    private String failureMessage(RunStepExecution step) {
        return step.problemCode() == null ? step.status().name() : step.problemCode();
    }

    /**
     * JUnit 문제 유형 문자열을 결정한다.
     */
    private String problemType(RunStepExecution step) {
        return step.problemCode() == null ? step.status().name() : step.problemCode();
    }

    /**
     * XML 특수 문자를 이스케이프한다.
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** JUnit testcase의 출력 종류다. */
    private enum ResultKind {
        PASSED,
        FAILURE,
        ERROR,
        SKIPPED
    }

    /**
     * JUnit testcase를 출력하기 전 보관하는 불변 값이다.
     *
     * @param name testcase 이름
     * @param kind 출력 결과 종류
     * @param message 실패, 오류 또는 건너뜀 설명
     * @param type 실패 또는 오류 유형
     */
    private record TestCaseResult(String name, ResultKind kind, String message, String type) {
    }
}
