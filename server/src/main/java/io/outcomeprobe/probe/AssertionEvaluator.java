package io.outcomeprobe.probe;

import tools.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 문서에 assertion 목록을 평가하는 순수 서비스다.
 *
 * <p>책임: Probe 수렴 판정과 target.http 응답 검사가 동일한 규칙으로
 * assertion 결과를 얻게 한다.</p>
 * <p>핵심 불변식: 개별 assertion 결과는 전부 기록돼, 실패 시 어느 조건이
 * 왜 실패했는지 Report에 남는다.</p>
 */
public class AssertionEvaluator {

    /**
     * 기본 생성자다. 평가는 상태 없이 수행된다.
     */
    public AssertionEvaluator() {
    }

    /**
     * 문서에 assertion 전부를 평가한다.
     *
     * @param document 평가할 JSON 문서
     * @param assertions assertion 목록
     * @return 전체 통과 여부와 개별 결과
     */
    public AssertionResult evaluate(JsonNode document, List<AssertionSpec> assertions) {
        List<AssertionOutcome> outcomes = new ArrayList<>();
        boolean allPassed = true;
        for (AssertionSpec assertion : assertions) {
            JsonNode actual = extract(document, assertion.jsonPath());
            boolean passed = assertion.operator().evaluate(actual, assertion.expected());
            allPassed &= passed;
            outcomes.add(new AssertionOutcome(assertion.jsonPath(), assertion.operator(),
                    diagnosticValue(assertion.expected()),
                    diagnosticValue(actual), passed));
        }
        return new AssertionResult(allPassed, List.copyOf(outcomes));
    }

    /**
     * assertion 진단값을 JSON 구조를 잃지 않는 문자열로 변환한다.
     *
     * <p>문자열 스칼라는 기존처럼 따옴표 없이 기록하고 객체·배열은 JSON으로 기록한다.
     * missing은 관측값 부재를 뜻하므로 null을 반환한다. 이후 Probe redactor가 이 값을
     * 민감도와 크기 정책에 맞게 다시 제한한다.</p>
     *
     * @param value 기대 값 또는 실제 JsonNode
     * @return 구조를 보존한 진단 문자열, 값이 없으면 null
     */
    private String diagnosticValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            if (node.isMissingNode()) {
                return null;
            }
            if (node.isObject() || node.isArray()) {
                return node.toString();
            }
            return node.isNull() ? "null" : node.asText();
        }
        return String.valueOf(value);
    }

    /**
     * 문서에서 JSONPath로 값을 추출한다. 경로 없음은 missing 노드다.
     */
    private JsonNode extract(JsonNode document, String jsonPath) {
        if (document == null) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            Object value = JsonPath.read(document.toString(), jsonPath);
            if (value == null) {
                return tools.jackson.databind.node.NullNode.getInstance();
            }
            return new tools.jackson.databind.ObjectMapper().valueToTree(value);
        } catch (PathNotFoundException e) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        } catch (RuntimeException e) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
    }

    /**
     * 전체 assertion 평가 결과다.
     *
     * @param passed 전체 통과 여부
     * @param outcomes 개별 assertion 결과 목록
     */
    public record AssertionResult(boolean passed, List<AssertionOutcome> outcomes) {
    }

    /**
     * assertion 하나의 평가 결과다.
     *
     * @param jsonPath 평가한 경로
     * @param operator 적용 연산자
     * @param expected 기대 값 문자열
     * @param actual 실제 값 문자열, 없으면 null
     * @param passed 통과 여부
     */
    public record AssertionOutcome(String jsonPath, AssertionOperator operator, String expected,
                                   String actual, boolean passed) {
    }
}
