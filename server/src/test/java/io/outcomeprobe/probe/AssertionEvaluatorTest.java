package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AssertionEvaluator}와 연산자 계약을 검증한다.
 *
 * <p>검출 가능한 결함: 숫자·문자열 타입 불일치의 조용한 통과, missing 경로에 대한
 * 잘못된 exists 판정, side effect 개수 비교 실패 누락.</p>
 */
class AssertionEvaluatorTest {

    private final AssertionEvaluator evaluator = new AssertionEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * JSON 문자열을 테스트용 문서 노드로 파싱한다.
     */
    private JsonNode document(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    /**
     * 숫자 동등 비교는 표현이 달라도 값이 같으면 통과해야 side effect count 검증이 정확하다.
     */
    @Test
    void numericEqualsByValue() throws Exception {
        JsonNode doc = document("{\"ledgerPostCount\": 1}");

        AssertionEvaluator.AssertionResult result = evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.ledgerPostCount", AssertionOperator.EQUALS, 1L)));

        assertThat(result.passed()).isTrue();
    }

    /**
     * missing 경로의 equals는 조용한 통과가 아니라 실패해야 상태 누락을 검출한다.
     */
    @Test
    void missingPathFailsEquals() throws Exception {
        JsonNode doc = document("{\"status\": \"SUCCEEDED\"}");

        AssertionEvaluator.AssertionResult result = evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.providerQueryCount", AssertionOperator.EQUALS, 1L)));

        assertThat(result.passed()).isFalse();
    }

    /**
     * greaterThanOrEqual은 수렴 후 최소 조회 횟수 검증에 사용된다.
     */
    @Test
    void greaterThanOrEqualPassesAtBoundary() throws Exception {
        JsonNode doc = document("{\"providerQueryCount\": 1}");

        AssertionEvaluator.AssertionResult result = evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.providerQueryCount",
                        AssertionOperator.GREATER_THAN_OR_EQUAL, "1")));

        assertThat(result.passed()).isTrue();
    }

    /** missing·문자열 값은 lessThan의 가장 작은 숫자로 오인돼서는 안 된다. */
    @Test
    void lessThanRejectsMissingAndNonNumericValues() throws Exception {
        JsonNode doc = document("{\"status\": \"PROCESSING\"}");

        AssertionEvaluator.AssertionResult missing = evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.missing", AssertionOperator.LESS_THAN, 10)));
        AssertionEvaluator.AssertionResult text = evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.status", AssertionOperator.LESS_THAN, 10)));

        assertThat(missing.passed()).isFalse();
        assertThat(text.passed()).isFalse();
    }

    /** missing 경로는 notEquals나 음수 size 기대값으로 우회 통과하지 않아야 한다. */
    @Test
    void missingPathFailsNonExistenceUnrelatedOperators() throws Exception {
        JsonNode doc = document("{}");

        assertThat(evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.missing", AssertionOperator.NOT_EQUALS, "x")))
                .passed()).isFalse();
        assertThat(evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.missing", AssertionOperator.SIZE_EQUALS, -1)))
                .passed()).isFalse();
    }

    /**
     * exists·notExists는 경로 존재 여부만 봐야 값과 무관하게 동작한다.
     */
    @Test
    void existsOperators() throws Exception {
        JsonNode doc = document("{\"status\": \"SUCCEEDED\"}");

        assertThat(evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.status", AssertionOperator.EXISTS, null))).passed())
                .isTrue();
        assertThat(evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.missing", AssertionOperator.NOT_EXISTS, null))).passed())
                .isTrue();
    }

    /**
     * 긴 입력의 정규식 매칭은 ReDoS 방지를 위해 거부돼야 한다.
     */
    @Test
    void rejectsTooLongRegexInput() throws Exception {
        String longText = "a".repeat(5000);
        JsonNode doc = document("{\"value\": \"" + longText + "\"}");

        AssertionEvaluator.AssertionResult result = evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.value", AssertionOperator.MATCHES_REGEX, "a+")));

        assertThat(result.passed()).isFalse();
    }

    /** 객체 assertion 진단값은 빈 문자열이 아니라 실제 JSON 구조를 보존해야 한다. */
    @Test
    void structuredOutcomesPreserveExpectedAndActualJson() throws Exception {
        JsonNode doc = document("{\"payload\":{\"state\":\"READY\",\"count\":1}}");
        JsonNode expected = document("{\"count\":1.0,\"state\":\"READY\"}");

        AssertionEvaluator.AssertionResult result = evaluator.evaluate(doc,
                List.of(new AssertionSpec("$.payload", AssertionOperator.EQUALS, expected)));

        assertThat(result.passed()).isTrue();
        assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.expected()).isEqualTo("{\"count\":1.0,\"state\":\"READY\"}");
            assertThat(outcome.actual()).isEqualTo("{\"state\":\"READY\",\"count\":1}");
        });
    }
}
