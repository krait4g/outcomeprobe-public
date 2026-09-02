package io.outcomeprobe.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 단일 운영자 Control API token을 검증한다.
 *
 * <p>책임: 설정 token 원문을 보존하지 않고 고정 길이 SHA-256 digest로 바꾼 뒤,
 * 요청 token도 같은 방식으로 정규화해 상수 시간 비교에 전달한다.</p>
 * <p>핵심 불변식: token 누락·공백·header에 안전하지 않은 문자는 시작 시 거부하고,
 * 비교 실패 원인이나 token 원문은 호출자와 로그에 제공하지 않는다.</p>
 */
@Component
public final class ControlApiTokenVerifier {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MINIMUM_TOKEN_LENGTH = 32;
    private static final int MAXIMUM_TOKEN_LENGTH = 512;
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[A-Za-z0-9._~+/-]+={0,2}");

    private final byte[] expectedDigest;

    /**
     * 환경에서 받은 운영자 token을 fail-closed 검증하고 digest만 보존한다.
     *
     * @param configuredToken 필수 Control API token
     * @throws IllegalArgumentException token이 길이·문자 형식 계약을 만족하지 않을 때
     */
    public ControlApiTokenVerifier(
            @Value("${outcomeprobe.security.control-api-token}") String configuredToken) {
        if (!isSafeToken(configuredToken)) {
            throw new IllegalArgumentException("Control API token 설정이 올바르지 않습니다.");
        }
        this.expectedDigest = sha256(configuredToken);
    }

    /**
     * Bearer scheme과 token을 엄격하게 분리한 뒤 설정값과 비교한다.
     *
     * <p>scheme은 HTTP 규칙에 맞춰 대소문자를 구분하지 않지만 token 자체는 정확히
     * 일치해야 한다. 앞뒤 공백이나 추가 credential은 허용하지 않는다.</p>
     *
     * @param authorizationHeader Authorization header 전체 값
     * @return 형식과 token이 모두 정확하면 {@code true}
     */
    public boolean matchesBearerHeader(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(
                        true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String providedToken = authorizationHeader.substring(BEARER_PREFIX.length());
        if (!isSafeToken(providedToken)) {
            return false;
        }
        // 두 입력을 고정 길이 digest로 만든 뒤 비교해 token 길이·접두사 일치 시간을 숨긴다.
        return MessageDigest.isEqual(expectedDigest, sha256(providedToken));
    }

    /**
     * token이 CLI·HTTP client가 공유하는 제한된 token68 계약인지 검사한다.
     *
     * @param token 검사할 token
     * @return 32~512자 ASCII 본문과 끝의 padding만 사용하면 {@code true}
     */
    private static boolean isSafeToken(String token) {
        return token != null
                && token.length() >= MINIMUM_TOKEN_LENGTH
                && token.length() <= MAXIMUM_TOKEN_LENGTH
                && TOKEN_PATTERN.matcher(token).matches();
    }

    /**
     * token 원문을 비교용 고정 길이 digest로 바꾼다.
     *
     * @param token digest를 계산할 token
     * @return SHA-256 digest
     * @throws IllegalStateException JVM이 필수 SHA-256 알고리즘을 제공하지 않을 때
     */
    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("필수 token digest 알고리즘을 사용할 수 없습니다.");
        }
    }
}
