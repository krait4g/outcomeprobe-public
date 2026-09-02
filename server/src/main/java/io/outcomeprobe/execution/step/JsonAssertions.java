package io.outcomeprobe.execution.step;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.probe.AssertionOperator;
import io.outcomeprobe.probe.AssertionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DSL assertion JSON을 타입 안전 {@link AssertionSpec}으로 해석하는 지원 도구다.
 *
 * <p>책임: equals·greaterThanOrEqual 등 연산자 필드명을 enum으로 매핑해
 * probe·target.http 실행기가 동일한 해석 규칙을 공유하게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 도구는 assertion 값의 업무 의미를 판단하지
 * 않는다. 구조 해석만 수행한다.</p>
 */
public final class JsonAssertions {

    /**
     * 정적 도구 클래스의 인스턴스화를 막는다.
     */
    private JsonAssertions() {
    }

    /**
     * assertion JSON 하나를 AssertionSpec으로 변환한다.
     *
     * @param node {@code {jsonPath, equals|greaterThanOrEqual|...}} 구조 노드
     * @return 변환된 AssertionSpec
     * @throws io.outcomeprobe.scenario.ArtifactImportException 알 수 없는 연산자면 발생한다
     */
    public static AssertionSpec parseSpec(JsonNode node) {
        String jsonPath = node.path("jsonPath").asText(null);
        if (jsonPath == null) {
            throw new io.outcomeprobe.scenario.ArtifactImportException(
                    ProblemCode.SCENARIO_SCHEMA_INVALID, "jsonPath", "assertion에 jsonPath가 필요합니다.");
        }
        if (node.has("equals")) {
            return new AssertionSpec(jsonPath, AssertionOperator.EQUALS, plain(node.get("equals")));
        }
        if (node.has("notEquals")) {
            return new AssertionSpec(jsonPath, AssertionOperator.NOT_EQUALS, plain(node.get("notEquals")));
        }
        if (node.has("exists") && node.get("exists").asBoolean()) {
            return new AssertionSpec(jsonPath, AssertionOperator.EXISTS, null);
        }
        if (node.has("notExists") && node.get("notExists").asBoolean()) {
            return new AssertionSpec(jsonPath, AssertionOperator.NOT_EXISTS, null);
        }
        if (node.has("contains")) {
            return new AssertionSpec(jsonPath, AssertionOperator.CONTAINS, plain(node.get("contains")));
        }
        if (node.has("matchesRegex")) {
            return new AssertionSpec(jsonPath, AssertionOperator.MATCHES_REGEX,
                    node.get("matchesRegex").asText());
        }
        if (node.has("in")) {
            List<Object> values = new ArrayList<>();
            node.get("in").forEach(element -> values.add(plain(element)));
            return new AssertionSpec(jsonPath, AssertionOperator.IN, values);
        }
        if (node.has("greaterThan")) {
            return new AssertionSpec(jsonPath, AssertionOperator.GREATER_THAN,
                    node.get("greaterThan").asText());
        }
        if (node.has("greaterThanOrEqual")) {
            return new AssertionSpec(jsonPath, AssertionOperator.GREATER_THAN_OR_EQUAL,
                    node.get("greaterThanOrEqual").asText());
        }
        if (node.has("lessThan")) {
            return new AssertionSpec(jsonPath, AssertionOperator.LESS_THAN,
                    node.get("lessThan").asText());
        }
        if (node.has("lessThanOrEqual")) {
            return new AssertionSpec(jsonPath, AssertionOperator.LESS_THAN_OR_EQUAL,
                    node.get("lessThanOrEqual").asText());
        }
        if (node.has("sizeEquals")) {
            return new AssertionSpec(jsonPath, AssertionOperator.SIZE_EQUALS,
                    node.get("sizeEquals").asText());
        }
        throw new io.outcomeprobe.scenario.ArtifactImportException(
                ProblemCode.SCENARIO_SCHEMA_INVALID, jsonPath, "알 수 없는 assertion 연산자입니다.");
    }

    /**
     * DSL의 times 연산자 이름을 대문자 enum으로 변환한다.
     *
     * @param dslOperator {@code greaterThanOrEqual} 형식 문자열
     * @return 매핑된 AssertionOperator
     */
    public static AssertionOperator operatorFromDsl(String dslOperator) {
        return AssertionOperator.valueOf(dslOperator.toUpperCase(Locale.ROOT));
    }

    /**
     * JSON 스칼라는 호환되는 Java 값으로, 객체·배열은 안전한 복사본으로 변환한다.
     *
     * <p>객체·배열을 {@code asText()}로 축약하면 서로 다른 구조가 모두 빈 문자열처럼
     * 보일 수 있으므로 원래 JSON 타입과 계층을 보존한다.</p>
     *
     * @param node assertion에 선언된 기대 값 노드
     * @return 스칼라 Java 값 또는 독립된 JSON 컨테이너 복사본
     */
    private static Object plain(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isObject() || node.isArray()) {
            return node.deepCopy();
        }
        return node.asText();
    }
}
