package io.outcomeprobe.profile;

/**
 * secretRef를 안전하게 해석할 수 없음을 나타내는 예외다.
 *
 * <p>메시지와 필드에는 정규화된 참조 이름과 원인 분류만 포함하며 실제 secret 값이나
 * mounted file 경로를 포함하지 않는다.</p>
 */
public final class SecretResolutionException extends RuntimeException {

    private final String secretRef;

    /**
     * 안전한 참조 이름과 설명으로 예외를 만든다.
     *
     * @param secretRef 외부 노출 가능한 정규화 참조 이름
     * @param detail secret 값이 없는 안전한 오류 설명
     */
    private SecretResolutionException(String secretRef, String detail) {
        super(detail + ": " + safeReference(secretRef));
        this.secretRef = safeReference(secretRef);
    }

    /**
     * 필수 secret 설정 누락 예외를 만든다.
     *
     * @param secretRef 누락된 참조 이름
     * @return 안전한 누락 예외
     */
    public static SecretResolutionException missing(String secretRef) {
        return new SecretResolutionException(secretRef, "필수 secretRef를 해석할 수 없습니다");
    }

    /**
     * 허용하지 않는 참조 이름 예외를 만든다.
     *
     * @return 실제 입력을 노출하지 않는 참조 형식 예외
     */
    public static SecretResolutionException invalidReference() {
        return new SecretResolutionException("invalid-reference", "secretRef 형식이 올바르지 않습니다");
    }

    /**
     * 설정된 값이 빈 값·과대·개행 포함 등으로 안전하지 않은 예외를 만든다.
     *
     * @param secretRef 문제가 발생한 참조 이름
     * @return secret 값을 포함하지 않는 예외
     */
    public static SecretResolutionException invalidValue(String secretRef) {
        return new SecretResolutionException(secretRef, "secretRef 값이 HTTP header에 안전하지 않습니다");
    }

    /**
     * mounted secret을 안전하게 읽지 못한 예외를 만든다.
     *
     * @param secretRef 문제가 발생한 참조 이름
     * @return 파일 경로를 포함하지 않는 예외
     */
    public static SecretResolutionException sourceUnavailable(String secretRef) {
        return new SecretResolutionException(secretRef, "mounted secretRef를 안전하게 읽을 수 없습니다");
    }

    /**
     * 문제가 발생한 안전한 참조 이름을 반환한다.
     *
     * @return 정규화된 secretRef
     */
    public String secretRef() {
        return secretRef;
    }

    /** 예외 메시지에 넣을 수 있도록 참조 이름을 제한된 문자로 정리한다. */
    private static String safeReference(String value) {
        if (value == null || value.isBlank()) {
            return "missing-reference";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "?");
        return sanitized.substring(0, Math.min(sanitized.length(), 128));
    }
}
