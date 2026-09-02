package io.outcomeprobe.evidence;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import io.outcomeprobe.foundation.HashValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evidence 저장 전 secret과 민감 데이터를 가리는 redaction 규칙 집합이다.
 *
 * <p>책임: Authorization·Cookie 같은 header 원문과 사용자 지정 JSONPath 필드가
 * Timeline·Report에 새어 나가지 않게 한다.</p>
 * <p>핵심 불변식: redaction은 반드시 원본으로 matcher·서명 검증과 body hash를
 * 계산한 뒤에 적용한다. 순서가 바뀌면 검증과 증거가 서로 다른 데이터를 보게 된다.</p>
 */
public final class Redaction {

    /**
     * 기본적으로 항상 가리는 header 이름(소문자) 집합이다.
     */
    public static final Set<String> DEFAULT_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");

    private static final String REPLACEMENT = "***REDACTED***";

    /**
     * 정적 도구 클래스의 인스턴스화를 막는다.
     */
    private Redaction() {
    }

    /**
     * header 맵에서 민감 값을 대체한 새 맵을 반환한다.
     *
     * <p>Idempotency-Key는 원문 대신 해시 앞자리만 노출해 상관 추적은 가능하게 하되
     * 재사용 공격에 필요한 원문은 남기지 않는다.</p>
     *
     * @param headers 원본 header 맵
     * @param extraRedactNames 추가 가림 대상 header 이름(대소문자 무시)
     * @return redaction된 새 header 맵
     */
    public static Map<String, String> redactHeaders(Map<String, String> headers, List<String> extraRedactNames) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (DEFAULT_HEADERS.contains(lower) || extraRedactNames.stream()
                    .anyMatch(extra -> extra.equalsIgnoreCase(name))) {
                result.put(name, REPLACEMENT);
            } else if (lower.equals("idempotency-key")) {
                // 원문 재사용을 막으되 요청 추적은 가능하도록 해시 앞자리만 남긴다.
                String hashPrefix = HashValue.sha256(value).hexValue().substring(0, 12);
                result.put(name, "sha256:" + hashPrefix + "…");
            } else {
                result.put(name, value);
            }
        });
        return result;
    }

    /**
     * JSON 본문에서 지정 JSONPath 값들을 대체한 새 문자열을 반환한다.
     *
     * <p>경로가 없거나 본문을 해석할 수 없으면 원문을 그대로 반환한다. 이 경우에도
     * 저장 크기 제한은 호출자가 적용한다.</p>
     *
     * @param jsonBody 원본 JSON 문자열
     * @param jsonPaths 가림 대상 JSONPath 목록
     * @return redaction된 JSON 문자열
     */
    public static String redactJsonBody(String jsonBody, List<String> jsonPaths) {
        if (jsonBody == null || jsonPaths.isEmpty()) {
            return jsonBody;
        }
        try {
            com.jayway.jsonpath.DocumentContext context = JsonPath.parse(jsonBody);
            for (String path : jsonPaths) {
                try {
                    context.set(path, REPLACEMENT);
                } catch (PathNotFoundException ignored) {
                    // 경로가 없는 payload는 가릴 대상이 없으므로 그대로 둔다.
                }
            }
            return context.jsonString();
        } catch (RuntimeException e) {
            // JSON이 아닌 body는 구조적 redaction 대상이 아니다.
            return jsonBody;
        }
    }
}
