package io.outcomeprobe.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** JSONB가 바꿀 수 있는 key와 숫자 표기에도 canonical hash가 안정적인지 검증한다. */
class CanonicalJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** object key 순서와 지수·scale 차이는 같은 JSON 숫자 의미로 hash돼야 한다. */
    @Test
    void normalizesObjectOrderAndNumericLexicalForms() {
        var first = objectMapper.readTree(
                "{\"large\":1e2,\"small\":1.2300e-7,\"nested\":{\"b\":2,\"a\":1}}");
        var second = objectMapper.readTree(
                "{\"nested\":{\"a\":1.0,\"b\":2.00},\"small\":0.000000123,\"large\":100.0}");

        assertThat(CanonicalJson.sha256(objectMapper, first))
                .isEqualTo(CanonicalJson.sha256(objectMapper, second));
    }

    /** 배열 순서는 실행 의미이므로 같은 값의 순서가 바뀌면 hash도 달라야 한다. */
    @Test
    void preservesArrayOrder() {
        assertThat(CanonicalJson.sha256(objectMapper, objectMapper.readTree("[1,2,3]")))
                .isNotEqualTo(CanonicalJson.sha256(
                        objectMapper, objectMapper.readTree("[3,2,1]")));
    }
}
