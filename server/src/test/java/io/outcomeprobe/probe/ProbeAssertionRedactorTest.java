package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 JSONPath redaction과 assertion 진단 값 상한을 검증한다.
 *
 * <p>검증 불변식: exact뿐 아니라 parent·recursive·array wildcard 규칙도 민감한
 * expected·actual을 가리고, 큰 멀티바이트 값은 원문 대신 hash marker만 남긴다.</p>
 */
class ProbeAssertionRedactorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AssertionEvaluator evaluator = new AssertionEvaluator();

    /** exact 민감 경로의 expected와 actual을 모두 마스킹한다. */
    @Test
    void masksExpectedAndActualForExactPath() throws Exception {
        ProbeAssertionRedactor.RedactionResult result = redact(
                "{\"token\":\"actual-secret\",\"status\":\"PROCESSING\"}",
                List.of(new AssertionSpec("$.token", AssertionOperator.EQUALS,
                        "expected-secret")),
                List.of("$.token"));

        assertThat(result.applied()).isTrue();
        assertThat(result.outcomes().get(0).expected()).isEqualTo(ProbeAssertionRedactor.MASK);
        assertThat(result.outcomes().get(0).actual()).isEqualTo(ProbeAssertionRedactor.MASK);
        assertThat(mapper.writeValueAsString(result.outcomes()))
                .doesNotContain("expected-secret", "actual-secret");
    }

    /** 상위 객체 redaction은 하위 assertion 값까지 가려야 한다. */
    @Test
    void masksAssertionBelowRedactedParent() throws Exception {
        ProbeAssertionRedactor.RedactionResult result = redact(
                "{\"data\":{\"secret\":\"parent-secret\"}}",
                List.of(new AssertionSpec("$.data.secret", AssertionOperator.EQUALS,
                        "parent-secret")),
                List.of("$.data"));

        assertThat(result.outcomes().get(0).actual()).isEqualTo(ProbeAssertionRedactor.MASK);
    }

    /** recursive descent 규칙은 깊이에 관계없이 같은 필드 assertion을 가려야 한다. */
    @Test
    void masksRecursiveDescentRule() throws Exception {
        ProbeAssertionRedactor.RedactionResult result = redact(
                "{\"nested\":{\"token\":\"recursive-secret\"}}",
                List.of(new AssertionSpec("$.nested.token", AssertionOperator.EQUALS,
                        "recursive-secret")),
                List.of("$..token"));

        assertThat(result.outcomes().get(0).actual()).isEqualTo(ProbeAssertionRedactor.MASK);
    }

    /** array wildcard 규칙은 특정 index를 읽는 assertion 값을 가려야 한다. */
    @Test
    void masksArrayWildcardRule() throws Exception {
        ProbeAssertionRedactor.RedactionResult result = redact(
                "{\"users\":[{\"ssn\":\"array-secret\"}]}",
                List.of(new AssertionSpec("$.users[0].ssn", AssertionOperator.EQUALS,
                        "array-secret")),
                List.of("$.users[*].ssn"));

        assertThat(result.outcomes().get(0).actual()).isEqualTo(ProbeAssertionRedactor.MASK);
    }

    /** 4KiB를 넘는 멀티바이트 값은 어떤 원문 prefix도 남기지 않아야 한다. */
    @Test
    void replacesOversizedUtf8DiagnosticWithHashMarker() throws Exception {
        String large = "가".repeat(1400);
        ProbeAssertionRedactor.RedactionResult result = redact(
                mapper.createObjectNode().put("value", large).toString(),
                List.of(new AssertionSpec("$.value", AssertionOperator.EQUALS, large)),
                List.of());

        String actual = result.outcomes().get(0).actual();
        assertThat(actual).startsWith("[TRUNCATED bytes=").contains("sha256=");
        assertThat(actual).doesNotContain("가가가");
    }

    /** 문법이 잘못된 redaction JSONPath는 모든 assertion 값을 fail-closed 마스킹한다. */
    @Test
    void masksEveryOutcomeWhenRedactionPathIsInvalid() throws Exception {
        ProbeAssertionRedactor.RedactionResult result = redact(
                "{\"value\":\"must-not-leak\"}",
                List.of(new AssertionSpec("$.value", AssertionOperator.EQUALS,
                        "must-not-leak")),
                List.of("$[?"));

        assertThat(result.outcomes().get(0).expected()).isEqualTo(ProbeAssertionRedactor.MASK);
        assertThat(result.outcomes().get(0).actual()).isEqualTo(ProbeAssertionRedactor.MASK);
    }

    /** 규칙 적용 결과가 원본과 같아 성공 여부가 모호하면 모든 값을 가려야 한다. */
    @Test
    void masksEveryOutcomeWhenRedactionCannotBeObserved() throws Exception {
        ProbeAssertionRedactor.RedactionResult result = redact(
                "{\"value\":\"must-not-leak\"}",
                List.of(new AssertionSpec("$.value", AssertionOperator.EQUALS,
                        "must-not-leak")),
                List.of("$.missing"));

        assertThat(result.outcomes().get(0).actual()).isEqualTo(ProbeAssertionRedactor.MASK);
    }

    /** JSON 문자열과 assertions에 실제 redaction 로직을 적용한다. */
    private ProbeAssertionRedactor.RedactionResult redact(
            String json,
            List<AssertionSpec> assertions,
            List<String> redactionPaths) throws Exception {
        JsonNode body = mapper.readTree(json);
        AssertionEvaluator.AssertionResult original = evaluator.evaluate(body, assertions);
        return ProbeAssertionRedactor.redact(
                body, assertions, original, redactionPaths, evaluator);
    }
}
