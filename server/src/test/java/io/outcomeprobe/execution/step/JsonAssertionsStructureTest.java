package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.probe.AssertionSpec;
import org.junit.jupiter.api.Test;

/**
 * {@link JsonAssertions}가 JSON 기대 값의 타입과 구조를 보존하는지 검증한다.
 *
 * <p>검출 대상 결함: 객체·배열을 {@code asText()}로 축약해 서로 다른 구조가
 * 같은 빈 문자열로 판정되거나, {@code in}이 동등 비교와 다른 null 규칙을 쓰는 문제.</p>
 */
class JsonAssertionsStructureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 객체는 키 순서와 무관하게 중첩 값까지 비교하고 다른 구조는 거부한다. */
    @Test
    void equalsComparesObjectsRecursively() throws Exception {
        JsonNode actual = json("""
                {
                  "state": "READY",
                  "items": [{"id": 1, "enabled": true}, null]
                }
                """);
        AssertionSpec same = assertion("""
                {
                  "jsonPath": "$.payload",
                  "equals": {
                    "items": [{"enabled": true, "id": 1.0}, null],
                    "state": "READY"
                  }
                }
                """);
        AssertionSpec different = assertion("""
                {
                  "jsonPath": "$.payload",
                  "equals": {
                    "items": [{"enabled": false, "id": 1}, null],
                    "state": "READY"
                  }
                }
                """);
        AssertionSpec serialized = assertion(
                "{\"jsonPath\":\"$.payload\",\"equals\":\"{\\\"state\\\":\\\"READY\\\"}\"}");
        AssertionSpec notDifferent = assertion("""
                {
                  "jsonPath": "$.payload",
                  "notEquals": {
                    "items": [{"enabled": false, "id": 1}, null],
                    "state": "READY"
                  }
                }
                """);

        assertThat(same.expected()).isInstanceOf(JsonNode.class);
        assertThat(same.operator().evaluate(actual, same.expected())).isTrue();
        assertThat(different.operator().evaluate(actual, different.expected())).isFalse();
        assertThat(serialized.operator().evaluate(actual, serialized.expected())).isFalse();
        assertThat(notDifferent.operator().evaluate(actual, notDifferent.expected())).isTrue();
    }

    /** 배열은 길이와 순서를 보존하면서 중첩 객체와 숫자 값 의미까지 비교한다. */
    @Test
    void equalsComparesArraysInOrder() throws Exception {
        JsonNode actual = json("[1, true, null, {\"count\": 2}]");
        AssertionSpec same = assertion("""
                {
                  "jsonPath": "$.values",
                  "equals": [1.0, true, null, {"count": 2.00}]
                }
                """);
        AssertionSpec reordered = assertion("""
                {
                  "jsonPath": "$.values",
                  "equals": [true, 1, null, {"count": 2}]
                }
                """);

        assertThat(same.operator().evaluate(actual, same.expected())).isTrue();
        assertThat(reordered.operator().evaluate(actual, reordered.expected())).isFalse();
    }

    /** in은 객체·배열·null에도 equals와 동일한 재귀 비교 의미를 적용한다. */
    @Test
    void inReusesStructuralAndNullEquality() throws Exception {
        JsonNode objectActual = json("{\"state\": \"READY\", \"count\": 1}");
        AssertionSpec objectIn = assertion("""
                {
                  "jsonPath": "$.payload",
                  "in": [
                    {"state": "FAILED", "count": 1},
                    {"count": 1.0, "state": "READY"}
                  ]
                }
                """);
        AssertionSpec objectNotIn = assertion("""
                {
                  "jsonPath": "$.payload",
                  "in": [
                    {"state": "FAILED", "count": 1},
                    {"state": "READY", "count": 2}
                  ]
                }
                """);
        JsonNode arrayActual = json("[1, {\"enabled\": true}]");
        AssertionSpec arrayIn = assertion("""
                {
                  "jsonPath": "$.values",
                  "in": [[1.0, {"enabled": true}], [2, {"enabled": true}]]
                }
                """);
        AssertionSpec nullIn = assertion("""
                {"jsonPath": "$.value", "in": [null, "null"]}
                """);
        AssertionSpec onlyNullIn = assertion("""
                {"jsonPath": "$.value", "in": [null]}
                """);

        assertThat(objectIn.operator().evaluate(objectActual, objectIn.expected())).isTrue();
        assertThat(objectNotIn.operator().evaluate(objectActual, objectNotIn.expected())).isFalse();
        assertThat(arrayIn.operator().evaluate(arrayActual, arrayIn.expected())).isTrue();
        assertThat(nullIn.operator().evaluate(json("null"), nullIn.expected())).isTrue();
        assertThat(onlyNullIn.operator().evaluate(json("\"null\""), onlyNullIn.expected())).isFalse();
    }

    /** 숫자·boolean·null·문자열 스칼라는 기존 값 기반 호환 계약을 유지한다. */
    @Test
    void scalarValuesKeepTheirCompatibilityContract() throws Exception {
        AssertionSpec numeric = assertion("""
                {"jsonPath": "$.value", "equals": "1"}
                """);
        AssertionSpec bool = assertion("""
                {"jsonPath": "$.value", "equals": true}
                """);
        AssertionSpec nullValue = assertion("""
                {"jsonPath": "$.value", "equals": null}
                """);
        AssertionSpec text = assertion("""
                {"jsonPath": "$.value", "equals": "READY"}
                """);

        assertThat(numeric.operator().evaluate(json("1.00"), numeric.expected())).isTrue();
        assertThat(bool.operator().evaluate(json("true"), bool.expected())).isTrue();
        assertThat(nullValue.operator().evaluate(json("null"), nullValue.expected())).isTrue();
        assertThat(nullValue.operator().evaluate(json("\"null\""), nullValue.expected())).isFalse();
        assertThat(text.operator().evaluate(json("\"READY\""), text.expected())).isTrue();
    }

    /** assertion JSON 문자열을 실제 파서와 같은 ObjectMapper로 해석한다. */
    private AssertionSpec assertion(String source) throws Exception {
        return JsonAssertions.parseSpec(json(source));
    }

    /** JSON 문자열을 테스트용 노드로 해석한다. */
    private JsonNode json(String source) throws Exception {
        return objectMapper.readTree(source);
    }
}
