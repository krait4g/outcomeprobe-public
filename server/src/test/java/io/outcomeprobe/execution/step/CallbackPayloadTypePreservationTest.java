package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.outcomeprobe.emulator.TemplateRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Callback payload 렌더링 시 JSON 타입 보존을 검증한다.
 *
 * <p>검출 가능한 결함: events[].attributes의 숫자·boolean이 문자열로 변환되어
 * Target이 타입 불일치로 이벤트를 거부하거나 잘못된 상태로 처리하는 문제.</p>
 */
class CallbackPayloadTypePreservationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateRenderer templateRenderer = new TemplateRenderer();

    /**
     * renderPayload와 동일한 로직으로 attributes를 context에 넣고 렌더링한다.
     */
    private JsonNode renderWithAttributes(ObjectNode template, ObjectNode attributes) {
        Map<String, Object> renderContext = new LinkedHashMap<>();
        renderContext.put("resource.id", "res-1");
        renderContext.put("resource.type", "payment");
        renderContext.put("resource.state", "SUCCEEDED");
        if (attributes != null) {
            attributes.properties().forEach(entry ->
                    renderContext.put("resource.attributes." + entry.getKey(),
                            toJavaValue(entry.getValue())));
        }
        return templateRenderer.render(template, renderContext);
    }

    /**
     * CallbackScheduleStepExecutor.toJavaValue와 동일한 변환 로직이다.
     */
    private Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node;
    }

    /**
     * 숫자 타입 보존을 검증한다.
     */
    @Nested
    @DisplayName("숫자 타입 보존")
    class NumberPreservation {

        /**
         * 정수 attribute가 JSON number로 렌더링된다.
         */
        @Test
        @DisplayName("정수 37이 number 노드로 보존된다")
        void integerPreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("latitude", "${resource.attributes.latitude}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.put("latitude", 37);

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("latitude").isNumber()).isTrue();
            assertThat(result.get("latitude").asInt()).isEqualTo(37);
        }

        /**
         * 실수 attribute가 JSON number로 렌더링된다.
         */
        @Test
        @DisplayName("실수 37.5가 number 노드로 보존된다")
        void doublePreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("latitude", "${resource.attributes.latitude}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.put("latitude", 37.5);

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("latitude").isNumber()).isTrue();
            assertThat(result.get("latitude").asDouble()).isEqualTo(37.5);
        }

        /**
         * 큰 정수도 Long으로 보존된다.
         */
        @Test
        @DisplayName("큰 정수(epoch ms)가 number로 보존된다")
        void longPreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("timestamp", "${resource.attributes.event_ts_epoch_ms}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.put("event_ts_epoch_ms", 1785000000000L);

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("timestamp").isNumber()).isTrue();
            assertThat(result.get("timestamp").asLong()).isEqualTo(1785000000000L);
        }
    }

    /**
     * boolean·null·문자열 타입 보존을 검증한다.
     */
    @Nested
    @DisplayName("boolean·null·문자열 보존")
    class ScalarPreservation {

        /**
         * boolean attribute가 JSON boolean으로 렌더링된다.
         */
        @Test
        @DisplayName("boolean true가 boolean 노드로 보존된다")
        void booleanPreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("probability_uav", "${resource.attributes.probability_uav}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.put("probability_uav", true);

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("probability_uav").isBoolean()).isTrue();
            assertThat(result.get("probability_uav").asBoolean()).isTrue();
        }

        /**
         * 문자열 attribute는 JSON string으로 유지된다.
         */
        @Test
        @DisplayName("문자열이 text 노드로 유지된다")
        void stringPreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("radar_id", "${resource.attributes.radar_id}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.put("radar_id", "R1_92001");

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("radar_id").isTextual()).isTrue();
            assertThat(result.get("radar_id").asText()).isEqualTo("R1_92001");
        }

        /**
         * null attribute가 JSON null로 렌더링된다.
         */
        @Test
        @DisplayName("null이 null 노드로 보존된다")
        void nullPreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("optional_field", "${resource.attributes.optional_field}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.putNull("optional_field");

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("optional_field").isNull()).isTrue();
        }
    }

    /**
     * 중첩 객체·배열 보존을 검증한다.
     */
    @Nested
    @DisplayName("중첩 구조 보존")
    class NestedPreservation {

        /**
         * 배열 attribute가 JSON array로 보존된다.
         */
        @Test
        @DisplayName("배열이 array 노드로 보존된다")
        void arrayPreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("tags", "${resource.attributes.tags}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.putArray("tags").add("uav").add("confirmed");

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("tags").isArray()).isTrue();
            assertThat(result.get("tags").size()).isEqualTo(2);
            assertThat(result.get("tags").get(0).asText()).isEqualTo("uav");
        }

        /**
         * 중첩 객체 attribute가 JSON object로 보존된다.
         */
        @Test
        @DisplayName("중첩 객체가 object 노드로 보존된다")
        void objectPreserved() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("position", "${resource.attributes.position}");
            ObjectNode attributes = objectMapper.createObjectNode();
            ObjectNode position = attributes.putObject("position");
            position.put("lat", 37.5);
            position.put("lng", 127.0);

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("position").isObject()).isTrue();
            assertThat(result.get("position").get("lat").asDouble()).isEqualTo(37.5);
            assertThat(result.get("position").get("lng").asDouble()).isEqualTo(127.0);
        }
    }

    /**
     * 기존 payment callback 호환성을 검증한다.
     */
    @Nested
    @DisplayName("기존 callback 호환성")
    class BackwardCompatibility {

        /**
         * 기존 payment template(문자열 상태 + 숫자 금액)이 올바르게 렌더링된다.
         */
        @Test
        @DisplayName("payment callback: 상태 문자열 + 금액 숫자 동시 보존")
        void paymentCallbackCompatible() {
            ObjectNode template = objectMapper.createObjectNode();
            template.put("status", "${resource.state}");
            template.put("amount", "${resource.attributes.amount}");
            template.put("currency", "${resource.attributes.currency}");
            ObjectNode attributes = objectMapper.createObjectNode();
            attributes.put("amount", 15000);
            attributes.put("currency", "KRW");

            JsonNode result = renderWithAttributes(template, attributes);

            assertThat(result.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(result.get("amount").isNumber()).isTrue();
            assertThat(result.get("amount").asInt()).isEqualTo(15000);
            assertThat(result.get("currency").asText()).isEqualTo("KRW");
        }
    }
}
