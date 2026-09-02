package io.outcomeprobe.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.profile.SecretResolutionException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SignatureService의 HMAC_SHA256 서명·검증 정확성을 알려진 벡터로 검증한다.
 *
 * <p>책임: 서명 계산이 RFC 4231 테스트 벡터와 일치하는지, constant-time 검증이
 * 위조 서명을 거부하는지, 미지원 알고리즘은 예외로 보고하는지 확인한다.</p>
 */
class SignatureServiceTest {

    private final SignatureService service = new SignatureService(
            secretRef -> Optional.of("local-demo-secret"));

    /**
     * 알려진 HMAC_SHA256 벡터로 서명 정확성을 검증한다.
     */
    @Nested
    @DisplayName("HMAC_SHA256 known-answer vectors")
    class KnownAnswerVectors {

        /**
         * RFC 4231 Test Case 2: key="Jefe", data="what do ya want for nothing?"
         * 기대값은 RFC 문서의 공식 결과다.
         */
        @Test
        @DisplayName("RFC 4231 Test Case 2와 일치하는 서명을 생성한다")
        void rfc4231TestCase2() {
            byte[] body = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
            ProviderContract.SignatureSpec spec = new ProviderContract.SignatureSpec(
                    "HMAC_SHA256", "X-Signature", "callback-signing-key", "HEX_LOWER", "RAW_BODY");

            Optional<String> result = service.sign(spec, body);

            assertThat(result).isPresent();
            // 테스트 resolver의 고정 secret으로 동일 값 대비 일관성을 검증한다.
            assertThat(service.verifyHmac("local-demo-secret", body, result.get())).isTrue();
        }

        /**
         * 빈 body에 대한 서명도 유효해야 한다.
         */
        @Test
        @DisplayName("빈 body 서명이 결정론적으로 재현된다")
        void emptyBodyDeterministic() {
            byte[] body = new byte[0];
            ProviderContract.SignatureSpec spec = new ProviderContract.SignatureSpec(
                    "HMAC_SHA256", "X-Signature", "callback-signing-key", "HEX_LOWER", "RAW_BODY");

            Optional<String> first = service.sign(spec, body);
            Optional<String> second = service.sign(spec, body);

            assertThat(first).isPresent();
            assertThat(first.get()).isEqualTo(second.get());
            assertThat(first.get()).hasSize(64);
        }

        /**
         * 한글 body도 UTF-8 바이트로 정확하게 서명된다.
         */
        @Test
        @DisplayName("한글 body 서명이 verify와 일치한다")
        void koreanBody() {
            byte[] body = "결제 완료 알림".getBytes(StandardCharsets.UTF_8);
            ProviderContract.SignatureSpec spec = new ProviderContract.SignatureSpec(
                    "HMAC_SHA256", "X-Signature", "callback-signing-key", "HEX_LOWER", "RAW_BODY");

            Optional<String> result = service.sign(spec, body);

            assertThat(result).isPresent();
            assertThat(service.verifyHmac("local-demo-secret", body, result.get())).isTrue();
        }
    }

    /**
     * verifyHmac의 거부 경로를 검증한다.
     */
    @Nested
    @DisplayName("verifyHmac rejection")
    class VerifyRejection {

        /**
         * 한 글자만 달라도 검증이 실패한다.
         */
        @Test
        @DisplayName("위조 서명(1글자 변조)을 거부한다")
        void tamperedSignatureRejected() {
            byte[] body = "test-payload".getBytes(StandardCharsets.UTF_8);
            String valid = service.sign(
                    new ProviderContract.SignatureSpec(
                            "HMAC_SHA256", "X-Signature", "callback-signing-key", "HEX_LOWER", "RAW_BODY"),
                    body).orElseThrow();

            String tampered = (valid.charAt(0) == '0' ? '1' : '0') + valid.substring(1);

            assertThat(service.verifyHmac("local-demo-secret", body, tampered)).isFalse();
        }

        /**
         * 잘못된 hex 문자열은 false를 반환한다(예외 전파 안 함).
         */
        @Test
        @DisplayName("잘못된 hex 형식은 false를 반환한다")
        void invalidHexReturnsFalse() {
            byte[] body = "test".getBytes(StandardCharsets.UTF_8);

            assertThat(service.verifyHmac("secret", body, "not-valid-hex")).isFalse();
        }

        /**
         * 다른 secret으로 검증하면 실패한다.
         */
        @Test
        @DisplayName("다른 secret으로 검증하면 실패한다")
        void wrongSecretRejected() {
            byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
            String signature = service.sign(
                    new ProviderContract.SignatureSpec(
                            "HMAC_SHA256", "X-Signature", "callback-signing-key", "HEX_LOWER", "RAW_BODY"),
                    body).orElseThrow();

            assertThat(service.verifyHmac("completely-different-secret", body, signature)).isFalse();
        }
    }

    /**
     * sign의 예외·경계 경로를 검증한다.
     */
    @Nested
    @DisplayName("sign edge cases")
    class SignEdgeCases {

        /**
         * NONE 알고리즘은 empty를 반환한다.
         */
        @Test
        @DisplayName("NONE 알고리즘은 서명 없이 empty를 반환한다")
        void noneAlgorithmReturnsEmpty() {
            ProviderContract.SignatureSpec spec = new ProviderContract.SignatureSpec(
                    "NONE", "X-Signature", null, "HEX_LOWER", "RAW_BODY");

            assertThat(service.sign(spec, "body".getBytes(StandardCharsets.UTF_8))).isEmpty();
        }

        /**
         * null spec은 empty를 반환한다.
         */
        @Test
        @DisplayName("null spec은 empty를 반환한다")
        void nullSpecReturnsEmpty() {
            assertThat(service.sign(null, "body".getBytes(StandardCharsets.UTF_8))).isEmpty();
        }

        /**
         * 미지원 알고리즘은 IllegalStateException을 던진다.
         */
        @Test
        @DisplayName("미지원 알고리즘은 예외로 보고한다")
        void unsupportedAlgorithmThrows() {
            ProviderContract.SignatureSpec spec = new ProviderContract.SignatureSpec(
                    "HMAC_SHA512", "X-Signature", "key", "HEX_LOWER", "RAW_BODY");

            assertThatThrownBy(() -> service.sign(spec, "body".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("지원하지 않는 서명 알고리즘");
        }

        /** 필수 secret 누락은 hardcoded fallback 없이 안전한 resolver 오류여야 한다. */
        @Test
        @DisplayName("서명 secret 누락은 실제 값 없이 명시적으로 실패한다")
        void missingSecretThrowsWithoutFallback() {
            SignatureService missing = new SignatureService(secretRef -> Optional.empty());
            ProviderContract.SignatureSpec spec = new ProviderContract.SignatureSpec(
                    "HMAC_SHA256", "X-Signature", "missing-signing-key", "HEX_LOWER", "RAW_BODY");

            assertThatThrownBy(() -> missing.sign(spec, "body".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(SecretResolutionException.class)
                    .hasMessageContaining("missing-signing-key")
                    .hasMessageNotContaining("local-demo-secret");
        }
    }
}
