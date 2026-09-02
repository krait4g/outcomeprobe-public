package io.outcomeprobe.delivery;

import io.outcomeprobe.profile.SecretResolutionException;
import io.outcomeprobe.profile.SecretResolver;
import io.outcomeprobe.scenario.contract.ProviderContract;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Callback payload 서명을 생성하고 secret을 해석한다.
 *
 * <p>책임: Contract의 서명 계약(HMAC_SHA256, RAW_BODY, HEX_LOWER)에 따라
 * 원본 body 바이트의 서명을 계산한다.</p>
 * <p>핵심 불변식: 서명은 UTF-8 재직렬화가 아닌 수신·렌더링 원본 바이트로 계산한다.
 * secret 원문은 Evidence·로그에 남기지 않으며 공용 resolver가 환경변수 또는 mounted file에서
 * 실행 직전에만 해석한다.</p>
 */
@Service
public class SignatureService {

    private final SecretResolver secretResolver;

    /**
     * 공용 secret 해석 포트를 주입한다.
     *
     * @param secretResolver 환경변수·mounted file resolver
     */
    public SignatureService(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    /**
     * Contract 서명 계약에 따라 body 서명 header 값을 계산한다.
     *
     * <p>서명 계약이 없거나 NONE이면 empty를 반환한다. secret을 해석할 수 없으면
     * empty를 반환하지 않고 예외로 보고한다. 서명 없이 전송되면 Target이 신뢰할 수
     * 없는 이벤트를 받게 된다.</p>
     *
     * @param signature Contract 서명 계약, null 가능
     * @param rawBody 서명할 원본 body 바이트
     * @return header 값, 서명 없음이면 empty
     * @throws SecretResolutionException secret을 해석할 수 없을 때 발생한다
     * @throws IllegalStateException 알고리즘이 유효하지 않거나 HMAC 계산에 실패하면 발생한다
     */
    public Optional<String> sign(ProviderContract.SignatureSpec signature, byte[] rawBody) {
        if (signature == null || signature.algorithm() == null
                || "NONE".equalsIgnoreCase(signature.algorithm())) {
            return Optional.empty();
        }
        if (!"HMAC_SHA256".equalsIgnoreCase(signature.algorithm())) {
            throw new IllegalStateException("지원하지 않는 서명 알고리즘입니다: " + signature.algorithm());
        }
        String secret = secretResolver.resolveRequired(signature.secretRef());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody);
            return Optional.of(HexFormat.of().formatHex(digest));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC_SHA256 서명 계산에 실패했습니다.", e);
        }
    }

    /**
     * HMAC_SHA256 서명을 constant-time으로 검증한다.
     *
     * @param secret 서명 secret
     * @param rawBody 원본 body 바이트
     * @param providedHex 검증할 16진수 서명
     * @return 일치하면 true
     */
    public boolean verifyHmac(String secret, byte[] rawBody, String providedHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody);
            byte[] provided = HexFormat.of().parseHex(providedHex.trim());
            // 타이밍 공격으로 secret 유추를 막기 위해 상수 시간 비교를 사용한다.
            return MessageDigest.isEqual(expected, provided);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
