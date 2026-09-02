package io.outcomeprobe.infrastructure.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * HTTP 응답의 바이트 상한과 초과 원문 비노출을 검증한다.
 *
 * <p>검증 불변식: 최대 크기까지는 전체 바이트를 보존하고, 한 바이트라도 초과하면
 * 부분 응답이나 secret 조각을 결과에 남기지 않는다.</p>
 */
class BoundedResponseBodyTest {

    /** 최대 허용 바이트와 정확히 같은 응답은 손실 없이 반환해야 한다. */
    @Test
    void acceptsBodyAtExactByteLimit() throws Exception {
        byte[] body = "가나다".getBytes(StandardCharsets.UTF_8);

        BoundedResponseBody result = BoundedResponseBody.read(
                new ByteArrayInputStream(body), body.length);

        assertThat(result.tooLarge()).isFalse();
        assertThat(result.bytes()).containsExactly(body);
        assertThat(result.utf8Text()).isEqualTo("가나다");
    }

    /** 상한을 한 바이트 넘은 응답은 앞부분과 secret을 모두 버려야 한다. */
    @Test
    void dropsEveryByteWhenBodyExceedsLimit() throws Exception {
        byte[] body = "secret-token".getBytes(StandardCharsets.UTF_8);

        BoundedResponseBody result = BoundedResponseBody.read(
                new ByteArrayInputStream(body), body.length - 1);

        assertThat(result.tooLarge()).isTrue();
        assertThat(result.bytes()).isEmpty();
        assertThat(result.toString()).doesNotContain("secret-token");
    }

    /** 유효하지 않은 UTF-8 바이트는 대체 문자로 숨기지 않고 파싱 실패해야 한다. */
    @Test
    void rejectsMalformedUtf8() throws Exception {
        BoundedResponseBody result = BoundedResponseBody.read(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28}), 2);

        assertThatThrownBy(result::utf8Text)
                .isInstanceOf(CharacterCodingException.class);
    }
}
