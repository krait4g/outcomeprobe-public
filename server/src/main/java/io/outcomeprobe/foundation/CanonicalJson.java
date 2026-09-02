package io.outcomeprobe.foundation;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON 값을 key 순서와 무관한 canonical 표현으로 바꾸고 SHA-256을 계산한다.
 *
 * <p>Run snapshot은 PostgreSQL JSONB round-trip 과정에서 object key 순서가 바뀔 수 있다.
 * 따라서 일반 {@code JsonNode#toString()}을 곧바로 해시하면 내용이 같아도 다른 해시가
 * 나올 수 있다. 이 유틸리티는 object key를 재귀적으로 정렬하고 array 순서는 보존한다.</p>
 */
public final class CanonicalJson {

    /** 유틸리티 인스턴스 생성을 막는다. */
    private CanonicalJson() {
    }

    /**
     * 임의 객체를 canonical JSON으로 변환해 SHA-256 hex 값을 반환한다.
     *
     * @param objectMapper JSON 변환 mapper
     * @param value canonicalize할 값
     * @return 소문자 64자리 SHA-256
     */
    public static String sha256(ObjectMapper objectMapper, Object value) {
        JsonNode tree = value instanceof JsonNode node ? node : objectMapper.valueToTree(value);
        return HashValue.sha256(canonicalString(objectMapper, tree)).hexValue();
    }

    /**
     * object key를 재귀적으로 정렬한 독립 JSON tree를 반환한다.
     *
     * @param objectMapper 결과 tree 생성에 사용할 mapper
     * @param node canonicalize할 JSON tree
     * @return 입력과 storage를 공유하지 않는 canonical JSON tree
     */
    public static JsonNode canonicalize(ObjectMapper objectMapper, JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .forEach(entry -> sorted.set(entry.getKey(),
                            canonicalize(objectMapper, entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(element -> result.add(canonicalize(objectMapper, element)));
            return result;
        }
        return node.deepCopy();
    }

    /** object key와 숫자 표기까지 정규화한 JSON 문자열을 만든다. */
    private static String canonicalString(ObjectMapper objectMapper, JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder builder = new StringBuilder("{");
            boolean[] first = {true};
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .forEach(entry -> {
                        if (!first[0]) {
                            builder.append(',');
                        }
                        first[0] = false;
                        builder.append(objectMapper.valueToTree(entry.getKey()).toString())
                                .append(':')
                                .append(canonicalString(objectMapper, entry.getValue()));
                    });
            return builder.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(canonicalString(objectMapper, node.get(i)));
            }
            return builder.append(']').toString();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue().toString();
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal decimal = node.decimalValue();
            if (decimal.signum() == 0) {
                return "0";
            }
            return decimal.stripTrailingZeros().toPlainString();
        }
        return node.toString();
    }
}
