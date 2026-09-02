package io.outcomeprobe.emulator;

import tools.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import java.util.Locale;
import java.util.Map;

/**
 * provider.expect의 requestMatch 조건을 실제 요청에 평가한다.
 *
 * <p>책임: header 고정값·JSONPath 조건·path 변수 조건을 선언적으로 평가해
 * Scenario가 특정 요청에만 fault를 적용할 수 있게 한다.</p>
 * <p>핵심 불변식: matcher는 수신 원본에 대해서만 평가한다. redaction 이후 데이터로
 * 평가하면 서명·matcher가 서로 다른 데이터를 보게 된다.</p>
 */
public class RequestMatcherEvaluator {

    /**
     * 기본 생성자다. 평가는 상태 없이 수행된다.
     */
    public RequestMatcherEvaluator() {
    }

    /**
     * matcher JSON을 요청에 대해 평가한다.
     *
     * @param matcher {@code {headers: {...}, json: [{jsonPath, equals}]}} 구조, null이면 항상 통과
     * @param headers 수신 header 맵
     * @param body 수신 body 문자열, 없으면 null
     * @param pathVariables path template에서 추출한 변수
     * @return 모든 조건을 만족하면 true
     */
    public boolean matches(JsonNode matcher, Map<String, String> headers, String body,
                           Map<String, String> pathVariables) {
        if (matcher == null || matcher.isNull() || matcher.isEmpty()) {
            return true;
        }
        JsonNode headerConditions = matcher.get("headers");
        if (headerConditions != null && headerConditions.isObject()) {
            for (Map.Entry<String, String> entry : flattenText(headerConditions).entrySet()) {
                String actual = findHeaderIgnoreCase(headers, entry.getKey());
                if (actual == null || !actual.equals(entry.getValue())) {
                    return false;
                }
            }
        }
        JsonNode jsonConditions = matcher.get("json");
        if (jsonConditions != null && jsonConditions.isArray()) {
            for (JsonNode condition : jsonConditions) {
                String jsonPath = condition.path("jsonPath").asText(null);
                String expected = condition.path("equals").asText(null);
                if (jsonPath == null) {
                    return false;
                }
                Object actual = readJsonPath(body, jsonPath);
                if (actual == null || !String.valueOf(actual).equals(expected)) {
                    return false;
                }
            }
        }
        JsonNode pathConditions = matcher.get("path");
        if (pathConditions != null && pathConditions.isObject()) {
            for (Map.Entry<String, String> entry : flattenText(pathConditions).entrySet()) {
                if (!entry.getValue().equals(pathVariables.get(entry.getKey()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * body에서 JSONPath 값을 읽는다. 경로 없음·파싱 실패는 null이다.
     */
    private Object readJsonPath(String body, String jsonPath) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonPath.read(body, jsonPath);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * header 이름을 대소문자 구분 없이 찾는다.
     */
    private String findHeaderIgnoreCase(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * JSON 객체를 문자열 key/value map으로 평탄화한다.
     */
    private Map<String, String> flattenText(JsonNode node) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }
}
