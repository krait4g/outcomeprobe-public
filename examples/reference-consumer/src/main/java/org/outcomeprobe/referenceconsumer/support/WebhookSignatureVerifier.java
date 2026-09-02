package org.outcomeprobe.referenceconsumer.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Webhook 서명을 공통으로 검증하는 지원 컴포넌트다.
 *
 * <p>책임: 모든 Webhook endpoint가 동일한 HMAC_SHA256 검증 규칙을 사용하게 해
 * 검증 누락 endpoint가 생기는 것을 막는다.</p>
 * <p>핵심 불변식: 검증은 원본 body 바이트로 수행하며 상수 시간 비교로
 * 타이밍 정보를 노출하지 않는다.</p>
 */
@Component
public class WebhookSignatureVerifier {

    private final String signingSecret;

    /**
     * 서명 secret을 주입한다.
     *
     * @param signingSecret 데모 공용 서명 secret
     */
    public WebhookSignatureVerifier(
            @Value("${reference-consumer.webhook.signing-secret}")
            String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("Webhook 서명 secret은 비어 있을 수 없습니다.");
        }
        this.signingSecret = signingSecret;
    }

    /**
     * 원본 body와 제공된 서명을 검증한다.
     *
     * @param body 원본 요청 body
     * @param providedHex 16진수 서명 값, null이면 실패
     * @return 유효하면 true
     */
    public boolean isValid(byte[] body, String providedHex) {
        if (providedHex == null || providedHex.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] provided = hexToBytes(providedHex.trim().toLowerCase(Locale.ROOT));
            // 상수 시간 비교로 위조 시도의 타이밍 관측을 막는다.
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 16진수 문자열을 바이트 배열로 변환한다.
     */
    private byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }
}
