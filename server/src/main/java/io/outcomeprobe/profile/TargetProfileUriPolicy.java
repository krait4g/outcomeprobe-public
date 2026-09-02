package io.outcomeprobe.profile;

import java.net.URI;
import java.util.Locale;

/** Target Profile의 HTTP(S) origin과 상대 요청 경로를 안전하게 판정한다. */
public final class TargetProfileUriPolicy {

    /** 인스턴스 생성을 막는다. */
    private TargetProfileUriPolicy() {
    }

    /**
     * user-info·query·fragment·base path를 포함하지 않는 absolute HTTP(S) origin인지 확인한다.
     * 루트 slash 하나는 같은 origin의 표기 차이이므로 허용한다.
     *
     * @param baseUri 검증할 Target base URI
     * @return snapshot에 저장해도 안전한 URI이면 true
     */
    public static boolean isSafe(String baseUri) {
        if (baseUri == null || baseUri.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUri);
            String rawPath = uri.getRawPath();
            return uri.isAbsolute()
                    && !uri.isOpaque()
                    && ("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath))
                    && hasValidExplicitPort(uri);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Target origin 뒤에 결합할 상대 HTTP request-target이 안전한지 확인한다.
     *
     * <p>dot segment, network-path reference, fragment, backslash와 인코딩된 dot·slash·
     * backslash·percent를 거부한다. percent 자체의 이중 인코딩을 막아 adapter마다 다른
     * decode 횟수를 갖더라도 allowlist 경계를 우회하지 못하게 한다.</p>
     *
     * @param requestTarget query를 선택적으로 포함한 상대 request-target
     * @return origin 뒤에 안전하게 결합할 수 있으면 true
     */
    public static boolean isSafeRelativeRequestTarget(String requestTarget) {
        if (requestTarget == null || requestTarget.isEmpty() || !requestTarget.startsWith("/")
                || requestTarget.startsWith("//") || requestTarget.indexOf('\\') >= 0) {
            return false;
        }
        try {
            URI uri = URI.create(requestTarget);
            String rawPath = uri.getRawPath();
            if (uri.isAbsolute() || uri.isOpaque() || uri.getRawAuthority() != null
                    || uri.getRawFragment() != null || rawPath == null || !rawPath.startsWith("/")) {
                return false;
            }
            String lowerPath = rawPath.toLowerCase(Locale.ROOT);
            if (lowerPath.contains("%2e") || lowerPath.contains("%2f")
                    || lowerPath.contains("%5c") || lowerPath.contains("%25")) {
                return false;
            }
            return URI.create(rawPath).normalize().getRawPath().equals(rawPath);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 명시 port 문법이 있으면 URI parser가 유효한 1~65535 값으로 해석했는지 확인한다. */
    private static boolean hasValidExplicitPort(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return false;
        }
        int closingBracket = authority.lastIndexOf(']');
        int portSeparator = authority.lastIndexOf(':');
        boolean explicitPort = portSeparator >= 0
                && (closingBracket < 0 || portSeparator > closingBracket);
        if (!explicitPort) {
            return true;
        }
        return uri.getPort() >= 1 && uri.getPort() <= 65_535;
    }
}
