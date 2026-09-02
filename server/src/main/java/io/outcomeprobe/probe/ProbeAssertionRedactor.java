package io.outcomeprobe.probe;

import com.jayway.jsonpath.JsonPath;
import io.outcomeprobe.evidence.Redaction;
import io.outcomeprobe.foundation.HashValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Target Profile의 실제 JSONPath redaction을 Probe assertion 결과에 적용한다.
 *
 * <p>책임: 원본 body로 판정한 뒤 동일 body에 Profile JSONPath를 적용해 다시 평가하고,
 * 달라진 expected·actual을 고정 마스크로 바꾼다. parent·recursive·array wildcard 규칙도
 * body redaction 결과를 통해 안전하게 감지한다.</p>
 * <p>핵심 불변식: 민감 값과 4KiB를 넘는 진단 값은 DB, Evidence, Step 결과와 문제
 * 상세에 원문으로 전달되지 않는다.</p>
 */
public final class ProbeAssertionRedactor {

    /** 민감 assertion 값을 대신하는 고정 문자열이다. */
    public static final String MASK = "[REDACTED]";

    private static final int MAX_DIAGNOSTIC_BYTES = 4096;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 인스턴스 생성을 막는다. */
    private ProbeAssertionRedactor() {
    }

    /**
     * 원본과 실제 JSONPath redaction body의 assertion 결과를 비교해 민감 값을 가린다.
     *
     * <p>redaction 대상 경로가 원본에 없더라도 assertion expected 자체가 secret일 수
     * 있으므로 parent·wildcard 경로 포함 관계도 보수적으로 함께 검사한다.</p>
     *
     * @param originalBody 원본 HTTP JSON body
     * @param assertions 원본에 평가한 assertion 명세
     * @param originalResult 원본 body assertion 결과
     * @param redactionJsonPaths Profile redaction JSONPath 목록
     * @param assertionEvaluator redaction body 재평가기
     * @return 마스킹·크기 제한된 assertion 결과와 적용 여부
     */
    public static RedactionResult redact(
            JsonNode originalBody,
            List<AssertionSpec> assertions,
            AssertionEvaluator.AssertionResult originalResult,
            List<String> redactionJsonPaths,
            AssertionEvaluator assertionEvaluator) {
        List<String> protectedPaths = redactionJsonPaths == null
                ? List.of() : List.copyOf(redactionJsonPaths);
        RedactedEvaluation redactedEvaluation =
                evaluateRedactedBody(originalBody, assertions, protectedPaths, assertionEvaluator);
        List<AssertionEvaluator.AssertionOutcome> safe = new ArrayList<>();
        boolean applied = false;
        for (int index = 0; index < originalResult.outcomes().size(); index++) {
            AssertionEvaluator.AssertionOutcome original = originalResult.outcomes().get(index);
            String redactedActual = index < redactedEvaluation.outcomes().size()
                    ? redactedEvaluation.outcomes().get(index).actual() : original.actual();
            boolean protectedValue = !redactedEvaluation.succeeded()
                    || !Objects.equals(original.actual(), redactedActual)
                    || protectedPaths.stream()
                    .anyMatch(rule -> ruleCoversPath(rule, original.jsonPath()));
            String expected = protectedValue ? MASK : boundDiagnostic(original.expected());
            String actual = protectedValue ? MASK : boundDiagnostic(original.actual());
            applied |= protectedValue
                    || !Objects.equals(expected, original.expected())
                    || !Objects.equals(actual, original.actual());
            safe.add(new AssertionEvaluator.AssertionOutcome(original.jsonPath(),
                    original.operator(), expected, actual, original.passed()));
        }
        return new RedactionResult(List.copyOf(safe), applied);
    }

    /**
     * JSONPath가 적용된 별도 body를 만들고 같은 assertions를 다시 평가한다.
     */
    private static RedactedEvaluation evaluateRedactedBody(
            JsonNode originalBody,
            List<AssertionSpec> assertions,
            List<String> protectedPaths,
            AssertionEvaluator assertionEvaluator) {
        if (originalBody == null || protectedPaths.isEmpty()) {
            return new RedactedEvaluation(true, List.of());
        }
        try {
            // 잘못된 JSONPath를 Redaction이 원문 반환으로 숨기기 전에 먼저 검증한다.
            protectedPaths.forEach(JsonPath::compile);
            String originalJson = originalBody.toString();
            String redactedJson = Redaction.redactJsonBody(originalJson, protectedPaths);
            if (redactedJson.equals(originalJson)) {
                // 규칙이 적용되지 않았는지 내부 오류로 원문을 반환했는지 구분할 수 없어 전부 가린다.
                return new RedactedEvaluation(false, List.of());
            }
            JsonNode redactedBody = OBJECT_MAPPER.readTree(redactedJson);
            return new RedactedEvaluation(true,
                    assertionEvaluator.evaluate(redactedBody, assertions).outcomes());
        } catch (Exception exception) {
            // redaction 규칙이나 재파싱 실패는 모든 진단 값을 가리는 fail-closed 신호다.
            return new RedactedEvaluation(false, List.of());
        }
    }

    /**
     * redaction body 재평가 성공 여부와 assertion 결과를 함께 보존한다.
     *
     * @param succeeded JSONPath 적용과 재평가가 정상 완료됐는지 여부
     * @param outcomes redaction body의 assertion 결과
     */
    private record RedactedEvaluation(
            boolean succeeded,
            List<AssertionEvaluator.AssertionOutcome> outcomes) {
    }

    /**
     * parent·recursive descent·array wildcard 규칙이 assertion 경로를 덮는지 판정한다.
     */
    private static boolean ruleCoversPath(String rule, String assertionPath) {
        if (rule == null || assertionPath == null) {
            return false;
        }
        if (assertionPath.equals(rule)
                || assertionPath.startsWith(rule + ".")
                || assertionPath.startsWith(rule + "[")) {
            return true;
        }
        if (rule.startsWith("$..")) {
            String field = rule.substring(3);
            return assertionPath.contains("." + field)
                    || assertionPath.contains("['" + field + "']")
                    || assertionPath.contains("[\"" + field + "\"]");
        }
        if (!rule.contains("[*]")) {
            return false;
        }
        String[] parts = rule.split("\\[\\*\\]", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                regex.append("\\[\\d+\\]");
            }
            regex.append(Pattern.quote(parts[index]));
        }
        regex.append("(?:$|\\.|\\[)");
        return Pattern.compile(regex.toString()).matcher(assertionPath).find();
    }

    /**
     * 진단 값이 UTF-8 4KiB를 넘으면 원문 없이 전체값 해시와 길이만 반환한다.
     */
    private static String boundDiagnostic(String value) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_DIAGNOSTIC_BYTES) {
            return value;
        }
        return "[TRUNCATED bytes=" + bytes.length + " sha256="
                + HashValue.sha256(bytes).hexValue() + "]";
    }

    /**
     * redaction·크기 제한된 assertion 목록과 적용 여부를 함께 반환한다.
     *
     * @param outcomes 안전하게 변환된 assertion 결과
     * @param applied 하나 이상의 값이 마스킹 또는 크기 제한됐는지 여부
     */
    public record RedactionResult(
            List<AssertionEvaluator.AssertionOutcome> outcomes,
            boolean applied) {
    }
}
