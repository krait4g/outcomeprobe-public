package io.outcomeprobe.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * standalone HTML report의 동일 snapshot 렌더링·escape·fail-closed 정책을 검증한다.
 */
class HtmlReportServiceTest {

    private final JsonReportService jsonReportService = mock(JsonReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HtmlReportService service = new HtmlReportService(jsonReportService, objectMapper);

    /** buildReport는 조회한 단일 report map을 render API에 그대로 위임해야 한다. */
    @Test
    @DisplayName("조회한 JSON report snapshot을 standalone HTML로 렌더링한다")
    void buildReportRendersQueriedSnapshot() {
        Map<String, Object> report = Map.of(
                "runId", "run-1",
                "scenario", Map.of("name", "safe-scenario"),
                "status", "PASSED");
        when(jsonReportService.buildReport("run-1")).thenReturn(Optional.of(report));

        Optional<String> html = service.buildReport("run-1");

        assertThat(html).isPresent();
        assertThat(html.orElseThrow()).contains("safe-scenario", "PASSED");
    }

    /** 표시 문자열은 HTML escape하고 내장 JSON은 script 종료 문자를 만들지 않아야 한다. */
    @Test
    @DisplayName("Scenario와 script 내장 JSON의 HTML 주입 문자를 안전하게 이스케이프한다")
    void escapesHtmlAndScriptEmbedding() throws Exception {
        String attack = "</script><script>globalThis.compromised=true</script><img onerror='x'>";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", "run-1");
        report.put("scenario", Map.of("name", attack));
        report.put("status", "FAILED");
        report.put("explanation", attack);

        String html = service.render(report);
        String embeddedJson = between(html, "const report = ", ";\n");
        JsonNode restored = objectMapper.readTree(embeddedJson);

        assertThat(html).doesNotContain("</script><script>globalThis.compromised");
        assertThat(html).contains("&lt;/script&gt;&lt;script&gt;");
        assertThat(embeddedJson).contains("\\u003c/script\\u003e");
        assertThat(restored.path("scenario").path("name").asText()).isEqualTo(attack);
    }

    /** JSON 직렬화가 실패하면 빈 객체를 내장한 성공 HTML을 반환하지 않아야 한다. */
    @Test
    @DisplayName("HTML report JSON 직렬화 실패를 명시적인 예외로 전파한다")
    void serializationFailureIsFailClosed() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", "run-1");
        report.put("status", "ERROR");
        report.put("self", report);

        assertThatThrownBy(() -> service.render(report))
                .isInstanceOf(ReportSerializationException.class)
                .hasMessage("HTML report JSON을 직렬화할 수 없습니다.");
    }

    /** 두 marker 사이 문자열을 테스트용으로 추출한다. */
    private String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker) + startMarker.length();
        int end = source.indexOf(endMarker, start);
        return source.substring(start, end);
    }
}
