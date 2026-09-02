package io.outcomeprobe.report;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Run 결과를 외부 asset 없이 열리는 standalone HTML report로 변환한다.
 *
 * <p>책임: JSON report를 HTML 안에 임베드해 단일 파일로 공유·보관할 수 있게 한다.
 * CI artifact로 첨부하거나 메신저로 공유할 수 있다.</p>
 * <p>핵심 불변식: HTML은 JSON report의 표현일 뿐 내용을 바꾸지 않는다.
 * secret은 JSON 단계에서 이미 제외돼 있다.</p>
 */
@Service
public class HtmlReportService {

    private final JsonReportService jsonReportService;
    private final ObjectMapper objectMapper;

    /**
     * report 조립에 필요한 협업 객체를 주입한다.
     *
     * @param jsonReportService JSON report 서비스
     * @param objectMapper JSON 직렬화 mapper
     */
    public HtmlReportService(JsonReportService jsonReportService, ObjectMapper objectMapper) {
        this.jsonReportService = jsonReportService;
        this.objectMapper = objectMapper;
    }

    /**
     * standalone HTML report를 생성한다.
     *
     * @param runId Run 식별자
     * @return HTML 문자열, Run이 없으면 empty
     */
    public Optional<String> buildReport(String runId) {
        Optional<Map<String, Object>> report = jsonReportService.buildReport(runId);
        return report.map(this::render);
    }

    /**
     * 이미 고정된 JSON report map을 standalone HTML로 렌더링한다.
     *
     * <p>Bundle은 이 API를 사용해 report.json과 report.html이 같은 report snapshot을
     * 표현하게 한다. 직렬화 실패 시 빈 JSON으로 보정하지 않고 명시적인 예외를 던진다.</p>
     *
     * @param data HTML에 표시하고 내장할 JSON report
     * @return 외부 asset이 없는 HTML 문서
     * @throws ReportSerializationException report JSON을 안전하게 직렬화할 수 없으면 발생한다
     */
    public String render(Map<String, Object> data) {
        if (data == null) {
            throw new ReportSerializationException(
                    "HTML report를 생성할 수 없습니다.", null);
        }
        String status = String.valueOf(data.get("status"));
        String scenario = String.valueOf(data.get("scenario"));
        String explanation = data.get("explanation") == null ? "" : String.valueOf(data.get("explanation"));
        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JacksonException exception) {
            throw new ReportSerializationException(
                    "HTML report JSON을 직렬화할 수 없습니다.", exception);
        }
        // HTML raw-text인 script 안에서 태그 종료와 구문 분리를 만들 수 있는 문자를 제거한다.
        String safeJson = json
                .replace("&", "\\u0026")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
        String statusClass = "PASSED".equals(status) ? "passed" : ("FAILED".equals(status) ? "failed" : "error");
        String safeScenario = escapeHtml(scenario);
        String safeStatus = escapeHtml(status);
        String html = """
                <!DOCTYPE html>
                <html lang="ko">
                <head>
                <meta charset="UTF-8">
                <title>OutcomeProbe Report — %s</title>
                <style>
                  body { font-family: system-ui, sans-serif; background: #0f172a; color: #e2e8f0; margin: 0; padding: 32px; }
                  h1 { margin: 0 0 8px; }
                  .badge { display: inline-block; padding: 4px 14px; border-radius: 999px; font-weight: 700; }
                  .passed { background: #14532d; color: #86efac; }
                  .failed { background: #7f1d1d; color: #fca5a5; }
                  .error { background: #78350f; color: #fcd34d; }
                  .explanation { background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 12px 16px; margin: 16px 0; }
                  pre { background: #0b1220; border: 1px solid #334155; border-radius: 8px; padding: 16px; overflow-x: auto; font-size: 12px; }
                  .meta { color: #94a3b8; }
                </style>
                </head>
                <body>
                <h1>OutcomeProbe Report</h1>
                <p class="meta">Scenario: %s</p>
                <p><span class="badge %s">%s</span></p>
                %s
                <h2>Full JSON</h2>
                <pre id="json"></pre>
                <script>
                  const report = %s;
                  document.getElementById('json').textContent = JSON.stringify(report, null, 2);
                </script>
                </body>
                </html>
                """.formatted(safeScenario, safeScenario, statusClass, safeStatus,
                explanation.isEmpty() ? "" : "<div class=\"explanation\">" + escapeHtml(explanation) + "</div>",
                safeJson);
        return html;
    }

    /**
     * HTML 특수 문자를 이스케이프한다.
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
