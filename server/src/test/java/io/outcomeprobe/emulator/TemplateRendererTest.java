package io.outcomeprobe.emulator;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TemplateRenderer의 token 치환과 JSON 타입 보존을 검증한다.
 *
 * <p>책임: 단일 token은 값 타입(number, boolean)을 보존하고, 다중 token은
 * 문자열 보간으로 처리하며, token이 없으면 원본을 그대로 반환하는지 확인한다.</p>
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    /**
     * 단일 token 치환 시 JSON 타입 보존을 검증한다.
     */
    @Nested
    @DisplayName("단일 token 타입 보존")
    class SingleTokenTypes {

        /**
         * 정수 값이 문자열이 아닌 number 노드로 치환된다.
         */
        @Test
        @DisplayName("정수 token은 number 노드로 치환된다")
        void integerPreserved() {
            JsonNode template = JsonNodeFactory.instance.textNode("${resource.amount}");
            Map<String, Object> context = Map.of("resource.amount", 1500);

            JsonNode result = renderer.render(template, context);

            assertThat(result.isNumber()).isTrue();
            assertThat(result.asInt()).isEqualTo(1500);
        }

        /**
         * boolean 값이 문자열이 아닌 boolean 노드로 치환된다.
         */
        @Test
        @DisplayName("boolean token은 boolean 노드로 치환된다")
        void booleanPreserved() {
            JsonNode template = JsonNodeFactory.instance.textNode("${resource.active}");
            Map<String, Object> context = Map.of("resource.active", true);

            JsonNode result = renderer.render(template, context);

            assertThat(result.isBoolean()).isTrue();
            assertThat(result.asBoolean()).isTrue();
        }

        /**
         * 문자열 값은 text 노드로 유지된다.
         */
        @Test
        @DisplayName("문자열 token은 text 노드로 유지된다")
        void stringPreserved() {
            JsonNode template = JsonNodeFactory.instance.textNode("${resource.state}");
            Map<String, Object> context = Map.of("resource.state", "SUCCEEDED");

            JsonNode result = renderer.render(template, context);

            assertThat(result.isTextual()).isTrue();
            assertThat(result.asText()).isEqualTo("SUCCEEDED");
        }

        /**
         * null 값은 null 노드로 치환된다.
         */
        @Test
        @DisplayName("null token은 null 노드로 치환된다")
        void nullPreserved() {
            JsonNode template = JsonNodeFactory.instance.textNode("${resource.missing}");
            Map<String, Object> context = Map.of();

            JsonNode result = renderer.render(template, context);

            assertThat(result.isNull()).isTrue();
        }
    }

    /**
     * 다중 token 문자열 보간을 검증한다.
     */
    @Nested
    @DisplayName("다중 token 문자열 보간")
    class MultipleTokens {

        /**
         * 하나의 문자열에 여러 token이 있으면 문자열로 보간된다.
         */
        @Test
        @DisplayName("다중 token은 문자열로 보간된다")
        void multipleTokensInterpolated() {
            JsonNode template = JsonNodeFactory.instance.textNode("${resource.id}-payment-${resource.state}");
            Map<String, Object> context = Map.of(
                    "resource.id", "pay_001",
                    "resource.state", "SUCCEEDED");

            JsonNode result = renderer.render(template, context);

            assertThat(result.isTextual()).isTrue();
            assertThat(result.asText()).isEqualTo("pay_001-payment-SUCCEEDED");
        }

        /**
         * token이 없는 문자열은 그대로 반환된다.
         */
        @Test
        @DisplayName("token 없는 문자열은 원본 그대로다")
        void noTokenUnchanged() {
            JsonNode template = JsonNodeFactory.instance.textNode("plain-text");
            Map<String, Object> context = Map.of("resource.id", "ignored");

            JsonNode result = renderer.render(template, context);

            assertThat(result.asText()).isEqualTo("plain-text");
        }
    }

    /**
     * 중첩 JSON 구조 렌더링을 검증한다.
     */
    @Nested
    @DisplayName("중첩 JSON 구조")
    class NestedJson {

        /**
         * Object 내부의 token도 재귀적으로 치환된다.
         */
        @Test
        @DisplayName("Object 내부 token이 재귀적으로 치환된다")
        void objectRecursion() {
            ObjectNode template = JsonNodeFactory.instance.objectNode();
            template.put("status", "${resource.state}");
            template.put("amount", "${resource.amount}");
            template.put("fixed", "constant");

            Map<String, Object> context = Map.of(
                    "resource.state", "PROCESSING",
                    "resource.amount", 9900);

            JsonNode result = renderer.render(template, context);

            assertThat(result.get("status").asText()).isEqualTo("PROCESSING");
            assertThat(result.get("amount").asInt()).isEqualTo(9900);
            assertThat(result.get("fixed").asText()).isEqualTo("constant");
        }

        /**
         * null template은 null 노드를 반환한다.
         */
        @Test
        @DisplayName("null template은 null 노드를 반환한다")
        void nullTemplate() {
            JsonNode result = renderer.render(null, Map.of());

            assertThat(result.isNull()).isTrue();
        }

        /**
         * 숫자·boolean template은 deepCopy로 그대로 반환된다.
         */
        @Test
        @DisplayName("비문자열 스칼라는 그대로 반환된다")
        void scalarPassthrough() {
            JsonNode numberTemplate = JsonNodeFactory.instance.numberNode(42);
            JsonNode boolTemplate = JsonNodeFactory.instance.booleanNode(false);

            assertThat(renderer.render(numberTemplate, Map.of()).asInt()).isEqualTo(42);
            assertThat(renderer.render(boolTemplate, Map.of()).asBoolean()).isFalse();
        }
    }
}
