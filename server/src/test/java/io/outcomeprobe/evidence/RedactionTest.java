package io.outcomeprobe.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Redaction}의 secret 가림 규칙을 검증한다.
 *
 * <p>검출 가능한 결함: Authorization·Cookie 원문이 Evidence·Report에 남는 문제,
 * Idempotency-Key 원문이 재사용 가능하게 노출되는 문제.</p>
 */
class RedactionTest {

    /**
     * 기본 민감 header는 ***REDACTED***로 대체돼야 한다.
     */
    @Test
    void redactsDefaultSensitiveHeaders() {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer real-token",
                "Cookie", "session=abc",
                "Content-Type", "application/json");

        Map<String, String> redacted = Redaction.redactHeaders(headers, List.of());

        assertThat(redacted.get("Authorization")).isEqualTo("***REDACTED***");
        assertThat(redacted.get("Cookie")).isEqualTo("***REDACTED***");
        assertThat(redacted.get("Content-Type")).isEqualTo("application/json");
    }

    /**
     * Idempotency-Key는 원문 대신 해시 앞자리만 남아야 재사용 공격이 불가능하다.
     */
    @Test
    void redactsIdempotencyKeyToHashPrefix() {
        Map<String, String> headers = Map.of("Idempotency-Key", "kl-demo-1001");

        Map<String, String> redacted = Redaction.redactHeaders(headers, List.of());

        assertThat(redacted.get("Idempotency-Key")).startsWith("sha256:");
        assertThat(redacted.get("Idempotency-Key")).doesNotContain("kl-demo-1001");
    }

    /**
     * 사용자 지정 JSONPath 필드가 body에서 가려져야 한다.
     */
    @Test
    void redactsJsonBodyFields() {
        String body = "{\"customer\":{\"email\":\"a@b.com\"},\"amount\":100}";

        String redacted = Redaction.redactJsonBody(body, List.of("$.customer.email"));

        assertThat(redacted).doesNotContain("a@b.com");
        assertThat(redacted).contains("***REDACTED***");
        assertThat(redacted).contains("100");
    }
}
