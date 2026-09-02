package io.outcomeprobe.report.adapter.in.web;

import io.outcomeprobe.report.HtmlReportService;
import io.outcomeprobe.report.JUnitXmlReportService;
import io.outcomeprobe.report.JsonReportService;
import io.outcomeprobe.report.RunBundleService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run report를 제공하는 Control API다.
 *
 * <p>책임: CI와 UI가 같은 report 문서에 접근하게 한다. M1은 JSON report만
 * 제공하며 HTML·JUnit은 M2·M3에서 추가된다.</p>
 * <p>외부에서 오해하기 쉬운 한계: report는 실패를 보정하지 않는다. 관측 그대로를
 * 반환하며, 원인 추론은 규칙 기반 설명으로만 제공된다.</p>
 */
@RestController
@RequestMapping("/api/v1/runs/{runId}")
public class ReportController {

    private final JsonReportService jsonReportService;
    private final JUnitXmlReportService junitXmlReportService;
    private final RunBundleService runBundleService;
    private final HtmlReportService htmlReportService;

    /**
     * report 서비스들을 주입한다.
     *
     * @param jsonReportService JSON report 조립 서비스
     * @param junitXmlReportService JUnit XML report 서비스
     * @param runBundleService bundle export 서비스
     * @param htmlReportService standalone HTML report 서비스
     */
    public ReportController(JsonReportService jsonReportService,
                            JUnitXmlReportService junitXmlReportService,
                            RunBundleService runBundleService,
                            HtmlReportService htmlReportService) {
        this.jsonReportService = jsonReportService;
        this.junitXmlReportService = junitXmlReportService;
        this.runBundleService = runBundleService;
        this.htmlReportService = htmlReportService;
    }

    /**
     * JSON report를 조회한다.
     *
     * @param runId Run 식별자
     * @return report 문서 또는 404
     */
    @GetMapping("/reports/json")
    public ResponseEntity<Map<String, Object>> jsonReport(@PathVariable String runId) {
        return jsonReportService.buildReport(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * JUnit XML report를 조회한다.
     *
     * @param runId Run 식별자
     * @return XML 문서 또는 404
     */
    @GetMapping(value = "/reports/junit", produces = "application/xml")
    public ResponseEntity<String> junitReport(@PathVariable String runId) {
        return junitXmlReportService.buildReport(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * standalone HTML report를 조회한다.
     *
     * <p>외부 asset 없이 단일 파일로 열리며 CI artifact로 공유할 수 있다.</p>
     *
     * @param runId Run 식별자
     * @return HTML 문서 또는 404
     */
    @GetMapping(value = "/reports/html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> htmlReport(@PathVariable String runId) {
        return htmlReportService.buildReport(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Run 증거 전체를 zip bundle로 내려받는다.
     *
     * @param runId Run 식별자
     * @return zip 바이트 또는 404
     */
    @GetMapping(value = "/bundle", produces = "application/zip")
    public ResponseEntity<byte[]> bundle(@PathVariable String runId) {
        return runBundleService.buildBundle(runId)
                .map(bytes -> ResponseEntity.ok()
                        .header("Content-Disposition",
                                "attachment; filename=\"" + runId + "-bundle.zip\"")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
