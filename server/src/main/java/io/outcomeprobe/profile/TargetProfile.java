package io.outcomeprobe.profile;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OutcomeProbe가 요청을 보낼 수 있는 Target Application의 신뢰 경계 정의다.
 *
 * <p>책임: base URI·허용 path·허용 method·redaction 규칙을 고정해 Scenario가
 * 임의 호스트로 요청하지 못하게 막는 SSRF 방어 기준을 제공한다.</p>
 * <p>핵심 불변식: secret 값은 이 모델에도 존재하지 않는다. 오직 secretRef 이름만
 * 보유하며 실제 값은 실행 시 SecretResolver가 해석한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: path allowlist는 검증된 glob 패턴만 지원한다.
 * 정규식 입력은 허용하지 않는다.</p>
 *
 * @param name Profile 이름
 * @param baseUri Target base URI 문자열
 * @param allowedPaths 허용 경로 glob 목록
 * @param allowedMethods 허용 HTTP method 목록
 * @param allowedHosts 허용 host 목록(와일드카드 *.도메인 가능), 비어 있으면 baseUri host만 허용
 * @param allowedPorts 허용 port 목록, 비어 있으면 제한 없음
 * @param defaultHeaders 매 요청에 추가되는 안전 header
 * @param secretHeaders header명 → secretRef 이름
 * @param callbackPaths callback key → 수신 상대 경로
 * @param redactionHeaderNames 추가 redaction header 이름
 * @param redactionJsonPaths redaction할 body JSONPath 목록
 */
public record TargetProfile(
        String name,
        String baseUri,
        List<String> allowedPaths,
        List<String> allowedMethods,
        List<String> allowedHosts,
        List<Integer> allowedPorts,
        Map<String, String> defaultHeaders,
        Map<String, String> secretHeaders,
        Map<String, String> callbackPaths,
        List<String> redactionHeaderNames,
        List<String> redactionJsonPaths) {

    /**
     * Profile collection을 방어적으로 복사해 Run snapshot이 외부 변경에 흔들리지 않게 한다.
     */
    public TargetProfile {
        if (!TargetProfileUriPolicy.isSafe(baseUri)) {
            throw new IllegalArgumentException(
                    "Target Profile baseUri는 credential 없는 absolute HTTP(S) origin이어야 합니다.");
        }
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        allowedPorts = allowedPorts == null ? List.of() : List.copyOf(allowedPorts);
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        secretHeaders = secretHeaders == null ? Map.of() : Map.copyOf(secretHeaders);
        callbackPaths = callbackPaths == null ? Map.of() : Map.copyOf(callbackPaths);
        redactionHeaderNames = redactionHeaderNames == null
                ? List.of() : List.copyOf(redactionHeaderNames);
        redactionJsonPaths = redactionJsonPaths == null
                ? List.of() : List.copyOf(redactionJsonPaths);
    }

    /**
     * 상대 경로가 이 Profile의 allowlist에 포함되는지 검증한다.
     *
     * <p>path traversal과 percent-encoded 우회를 먼저 거부한 뒤 glob 매칭을 수행한다.
     * 이 순서가 바뀌면 allowlist가 무의미해진다.</p>
     *
     * @param path 검사할 상대 경로
     * @return 허용되면 true
     */
    public boolean isPathAllowed(String path) {
        if (!TargetProfileUriPolicy.isSafeRelativeRequestTarget(path)) {
            return false;
        }
        String pathOnly = java.net.URI.create(path).getRawPath();
        return allowedPaths.stream().anyMatch(pattern -> globToRegex(pattern).matcher(pathOnly).matches());
    }

    /**
     * 요청 URI의 host·port가 이 Profile의 network policy에 허용되는지 검증한다.
     *
     * <p>allowedHosts가 비어 있으면 baseUri의 host만 허용한다. port는 allowedPorts가
     * 비어 있으면 제한하지 않는다. 이 검사는 Adapter에서 NetworkSafety와 함께
     * 요청 직전에 수행된다(OP-AUDIT-SEC-001).</p>
     *
     * @param uri 검사할 요청 URI
     * @return 허용되면 true
     */
    public boolean isHostAllowed(java.net.URI uri) {
        if (!isOriginHostAllowed(uri)) {
            return false;
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return allowedPorts.isEmpty() || allowedPorts.contains(port);
    }

    /**
     * URI host만 Profile allowlist에 포함되는지 port와 분리해 판정한다.
     *
     * <p>사전 검증 결과에서 host와 port 실패 원인을 정확히 구분하기 위한 API다.
     * 실제 outbound 경계에서는 둘을 함께 검사하는 {@link #isHostAllowed(java.net.URI)}를 사용한다.</p>
     *
     * @param uri 검사할 요청 URI
     * @return host 이름이 허용되면 true
     */
    public boolean isOriginHostAllowed(java.net.URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost();
        List<String> effectiveHosts = allowedHosts.isEmpty()
                ? List.of(java.net.URI.create(baseUri).getHost())
                : allowedHosts;
        return effectiveHosts.stream().anyMatch(pattern -> hostMatches(pattern, host));
    }

    /**
     * HTTP method가 이 Profile의 allowedMethods에 포함되는지 검증한다.
     *
     * <p>method는 Locale.ROOT 기준 대문자로 정규화해 비교한다. allowedMethods가
     * 비어 있으면 모든 method를 허용한다(하위 호환).</p>
     *
     * @param method 검사할 HTTP method
     * @return 허용되면 true
     */
    public boolean isMethodAllowed(String method) {
        if (allowedMethods.isEmpty()) {
            return true;
        }
        if (method == null || method.isBlank()) {
            return false;
        }
        String normalized = method.toUpperCase(java.util.Locale.ROOT);
        return allowedMethods.stream()
                .anyMatch(allowed -> allowed.toUpperCase(java.util.Locale.ROOT).equals(normalized));
    }

    /**
     * host 패턴을 매칭한다. *.도메인 와일드카드를 지원한다.
     */
    private boolean hostMatches(String pattern, String host) {
        if (pattern == null) {
            return false;
        }
        if (pattern.startsWith("*.")) {
            return host.toLowerCase(java.util.Locale.ROOT)
                    .endsWith(pattern.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return pattern.equalsIgnoreCase(host);
    }

    /**
     * glob 패턴을 정규식으로 변환한다. **는 하위 경로 전체, *는 단일 세그먼트다.
     */
    private Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // ** 는 하위 경로 전체를 허용한다.
                    regex.append(".*");
                    i += 2;
                    if (i < glob.length() && glob.charAt(i) == '/') {
                        i++;
                    }
                    continue;
                }
                regex.append("[^/]*");
            } else if ("\\.[]{}()+-?^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
            i++;
        }
        return Pattern.compile(regex.append("$").toString());
    }
}
