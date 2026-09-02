package io.outcomeprobe.foundation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 해시 결과를 불변 값 객체로 표현한다.
 *
 * <p>책임: Artifact content hash, 요청 body hash, idempotency key hash처럼
 * 증거와 무결성 검증에 사용되는 해시를 원문 데이터 대신 안전하게 주고받는다.</p>
 * <p>핵심 불변식: 이 객체는 항상 소문자 16진수 64자의 유효한 SHA-256 표현만 보유한다.
 * 해시 원문(예: secret)은 이 객체로부터 역산할 수 없으며 로그·Evidence에 원문을 남길 때는
 * 별도 redaction 정책을 거친다.</p>
 *
 * @param hexValue 소문자 16진수 64자로 표현된 SHA-256 값
 */
public record HashValue(String hexValue) {

    /**
     * 해시 값의 형식을 검증하고 인스턴스를 생성한다.
     *
     * @param hexValue 소문자 16진수 64자 문자열
     * @throws IllegalArgumentException SHA-256 16진수 형식이 아니면 발생한다
     */
    public HashValue {
        if (hexValue == null || !hexValue.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("SHA-256 해시는 소문자 16진수 64자여야 합니다.");
        }
    }

    /**
     * 바이트 배열의 SHA-256 해시를 계산한다.
     *
     * <p>redaction 이전 원본 바이트에 대해 호출해야 matcher·서명 검증과 Evidence hash가
     * 같은 입력을 공유한다.</p>
     *
     * @param bytes 해시를 계산할 원본 바이트
     * @return 계산된 해시 값
     */
    public static HashValue sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new HashValue(HexFormat.of().formatHex(digest.digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 의무 지원하는 알고리즘이므로 이 경로는 정상 환경에서 도달할 수 없다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    /**
     * UTF-8 문자열의 SHA-256 해시를 계산한다.
     *
     * @param text 해시를 계산할 문자열
     * @return 계산된 해시 값
     */
    public static HashValue sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
