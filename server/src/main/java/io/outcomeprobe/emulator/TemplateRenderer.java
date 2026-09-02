package io.outcomeprobe.emulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract 응답·Callback template의 허용 변수를 실제 값으로 치환한다.
 *
 * <p>책임: {@code ${resource.state}} 같은 token을 JSON 타입을 보존하면서 치환해
 * 숫자·boolean이 문자열로 깨지지 않게 한다.</p>
 * <p>핵심 불변식: 허용 token source(variables, resource, generated, clock, request)
 * 밖의 임의 환경변수·파일·script 조회는 지원하지 않는다. 문자열 치환 후 JSON을
 * 다시 parse하는 방식은 타입을 잃으므로 사용하지 않는다.</p>
 */
public class TemplateRenderer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * 기본 생성자다. 렌더링은 상태 없이 수행된다.
     */
    public TemplateRenderer() {
    }

    /**
     * JSON template 트리를 컨텍스트 값으로 렌더링한 새 트리를 반환한다.
     *
     * @param template 렌더링할 JSON template
     * @param context dot 경로로 표현된 값 컨텍스트
     * @return 렌더링된 새 JSON 트리
     */
    public JsonNode render(JsonNode template, Map<String, Object> context) {
        if (template == null || template.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (template.isTextual()) {
            return renderText(template.asText(), context);
        }
        if (template.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> entry : template.properties()) {
                result.set(entry.getKey(), render(entry.getValue(), context));
            }
            return result;
        }
        if (template.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            template.forEach(element -> result.add(render(element, context)));
            return result;
        }
        return template.deepCopy();
    }

    /**
     * 문자열 노드의 token을 치환한다. 단일 token은 값 타입을 보존한다.
     */
    private JsonNode renderText(String text, Map<String, Object> context) {
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        // 전체 문자열이 단일 token이면 값 타입을 그대로 보존한다.
        if (matcher.matches()) {
            Object value = context.get(matcher.group(1));
            return toJsonNode(value);
        }
        matcher.reset();
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            Object value = context.get(matcher.group(1));
            matcher.appendReplacement(builder, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(builder);
        return JsonNodeFactory.instance.textNode(builder.toString());
    }

    /**
     * Java 값을 JSON 노드로 변환한다. 타입을 문자열로 납작하게 만들지 않는다.
     *
     * <p>JsonNode 값(중첩 객체·배열)은 그대로 반환해 타입을 보존한다.</p>
     */
    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode.deepCopy();
        }
        if (value instanceof String string) {
            return JsonNodeFactory.instance.textNode(string);
        }
        if (value instanceof Integer integer) {
            return JsonNodeFactory.instance.numberNode(integer);
        }
        if (value instanceof Long longValue) {
            return JsonNodeFactory.instance.numberNode(longValue);
        }
        if (value instanceof Double doubleValue) {
            return JsonNodeFactory.instance.numberNode(doubleValue);
        }
        if (value instanceof Boolean booleanValue) {
            return JsonNodeFactory.instance.booleanNode(booleanValue);
        }
        return JsonNodeFactory.instance.textNode(String.valueOf(value));
    }
}
