package io.outcomeprobe.probe;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Probe·Target assertion이 지원하는 비교 연산자다.
 *
 * <p>책임: JSONPath로 추출한 실제 값과 기대 값을 결정론적으로 비교한다.</p>
 * <p>핵심 불변식: 객체는 키 집합과 값을, 배열은 길이·순서·값을 재귀적으로 비교한다.
 * 정규식 연산자는 ReDoS 방지를 위해 입력·패턴 길이와 실행시간을 제한한다.
 * 타입 불일치는 조용한 통과가 아니라 명시적 실패다.</p>
 */
public enum AssertionOperator {

    // 동등 비교다. 스칼라는 값 의미로, 객체·배열은 재귀적 JSON 구조로 비교한다.
    EQUALS,

    // 부등 비교다.
    NOT_EQUALS,

    // 경로 존재 요구다.
    EXISTS,

    // 경로 부재 요구다.
    NOT_EXISTS,

    // 문자열 부분 포함이다.
    CONTAINS,

    // 정규식 전체 매칭이다.
    MATCHES_REGEX,

    // 동등 비교와 같은 의미로 허용 값 목록 포함 여부를 판정한다.
    IN,

    // 숫자 초과 비교다.
    GREATER_THAN,

    // 숫자 이상 비교다.
    GREATER_THAN_OR_EQUAL,

    // 숫자 미만 비교다.
    LESS_THAN,

    // 숫자 이하 비교다.
    LESS_THAN_OR_EQUAL,

    // 배열·문자열 크기 동등이다.
    SIZE_EQUALS;

    /**
     * 실제 값을 이 연산자로 평가한다.
     *
     * @param actual JSONPath 추출 실제 값 노드, 없으면 missing 노드
     * @param expected 기대 값, 연산자에 따라 null 가능
     * @return 충족 여부
     */
    public boolean evaluate(JsonNode actual, Object expected) {
        return switch (this) {
            case EXISTS -> !actual.isMissingNode() && !actual.isNull();
            case NOT_EXISTS -> actual.isMissingNode() || actual.isNull();
            case EQUALS -> isEqual(actual, expected);
            case NOT_EQUALS -> !actual.isMissingNode() && !isEqual(actual, expected);
            case CONTAINS -> actual.isTextual() && expected != null
                    && actual.asText().contains(String.valueOf(expected));
            case MATCHES_REGEX -> matchesRegex(actual, expected);
            case IN -> inList(actual, expected);
            case GREATER_THAN -> compare(actual, expected, 1);
            case GREATER_THAN_OR_EQUAL -> compare(actual, expected, 0, 1);
            case LESS_THAN -> compare(actual, expected, -1);
            case LESS_THAN_OR_EQUAL -> compare(actual, expected, -1, 0);
            case SIZE_EQUALS -> sizeEquals(actual, expected);
        };
    }

    /**
     * 스칼라는 기존 호환 규칙으로, 객체·배열은 JSON 구조로 동등 비교한다.
     *
     * @param actual JSONPath로 추출한 실제 값
     * @param expected DSL 또는 호출자가 제공한 기대 값
     * @return 값 의미와 구조가 모두 같으면 true
     */
    private boolean isEqual(JsonNode actual, Object expected) {
        if (actual.isMissingNode()) {
            return false;
        }
        if (actual.isObject()) {
            return objectEquals(actual, expected);
        }
        if (actual.isArray()) {
            return arrayEquals(actual, expected);
        }
        if (expected instanceof JsonNode expectedNode) {
            if (expectedNode.isObject() || expectedNode.isArray()
                    || expectedNode.isMissingNode()) {
                return false;
            }
            expected = scalarValue(expectedNode);
        } else if (expected instanceof Map<?, ?> || expected instanceof List<?>) {
            return false;
        }
        if (actual.isNull()) {
            return expected == null;
        }
        if (expected == null) {
            return false;
        }
        if (actual.isNumber() && expected != null && isNumeric(String.valueOf(expected))) {
            return new BigDecimal(actual.asText()).compareTo(new BigDecimal(String.valueOf(expected))) == 0;
        }
        if (actual.isBoolean() && expected instanceof Boolean booleanValue) {
            return actual.asBoolean() == booleanValue;
        }
        return actual.asText().equals(String.valueOf(expected));
    }

    /**
     * JSON 객체를 키 순서와 무관하게 같은 키 집합과 재귀적 값 의미로 비교한다.
     *
     * @param actual 실제 JSON 객체
     * @param expected JSON 객체 노드 또는 문자열 키 Map
     * @return 구조와 모든 하위 값이 같으면 true
     */
    private boolean objectEquals(JsonNode actual, Object expected) {
        if (expected instanceof JsonNode expectedNode) {
            if (!expectedNode.isObject() || actual.size() != expectedNode.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> entry : actual.properties()) {
                if (!expectedNode.has(entry.getKey())
                        || !isEqual(entry.getValue(), expectedNode.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (!(expected instanceof Map<?, ?> expectedMap) || actual.size() != expectedMap.size()) {
            return false;
        }
        for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || !actual.has(key)
                    || !isEqual(actual.get(key), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * JSON 배열을 길이와 순서를 보존해 각 원소의 값 의미로 비교한다.
     *
     * @param actual 실제 JSON 배열
     * @param expected JSON 배열 노드 또는 Java List
     * @return 길이·순서·모든 하위 값이 같으면 true
     */
    private boolean arrayEquals(JsonNode actual, Object expected) {
        if (expected instanceof JsonNode expectedNode) {
            if (!expectedNode.isArray() || actual.size() != expectedNode.size()) {
                return false;
            }
            for (int index = 0; index < actual.size(); index++) {
                if (!isEqual(actual.get(index), expectedNode.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (!(expected instanceof List<?> expectedList) || actual.size() != expectedList.size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            if (!isEqual(actual.get(index), expectedList.get(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * JSON 스칼라 노드를 기존 Java 스칼라 비교 계약에 맞는 값으로 변환한다.
     *
     * @param node null·문자열·숫자·boolean 스칼라 노드
     * @return 비교에 사용할 Java 값
     */
    private Object scalarValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }

    /**
     * 정규식을 길이와 실행시간이 제한된 matcher로 평가한다.
     */
    private boolean matchesRegex(JsonNode actual, Object expected) {
        if (!actual.isTextual() || expected == null) {
            return false;
        }
        return SafeRegexMatcher.matches(String.valueOf(expected), actual.asText());
    }

    /**
     * 기대 값 목록 포함 여부를 판정한다.
     */
    private boolean inList(JsonNode actual, Object expected) {
        if (actual.isMissingNode() || !(expected instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(candidate -> isEqual(actual, candidate));
    }

    /** 숫자 비교가 허용된 결과 중 하나인지 확인하며 타입 불일치는 항상 실패시킨다. */
    private boolean compare(JsonNode actual, Object expected, int... accepted) {
        if (!actual.isNumber() || expected == null || !isNumeric(String.valueOf(expected))) {
            return false;
        }
        int comparison = new BigDecimal(actual.asText())
                .compareTo(new BigDecimal(String.valueOf(expected)));
        return java.util.Arrays.stream(accepted).anyMatch(value -> value == comparison);
    }

    /** 배열·문자열과 0 이상의 정수 기대값일 때만 크기 비교를 수행한다. */
    private boolean sizeEquals(JsonNode actual, Object expected) {
        int actualSize = sizeOf(actual);
        int expectedSize = asInt(expected);
        return actualSize >= 0 && expectedSize >= 0 && actualSize == expectedSize;
    }

    /**
     * 배열·문자열의 크기를 계산한다. 크기 측정 불가는 -1이다.
     */
    private int sizeOf(JsonNode actual) {
        if (actual.isArray()) {
            return actual.size();
        }
        if (actual.isTextual()) {
            return actual.asText().length();
        }
        return -1;
    }

    /**
     * 기대 값을 정수로 해석한다. 해석 실패는 -2로 구분한다.
     */
    private int asInt(Object expected) {
        try {
            return Integer.parseInt(String.valueOf(expected));
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    /**
     * 문자열이 숫자로 해석 가능한지 확인한다.
     */
    private boolean isNumeric(String text) {
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
