package io.outcomeprobe.profile;

import io.outcomeprobe.infrastructure.net.NetworkSafety;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Target Profile의 연결·안전성을 사전 검증한다.
 *
 * <p>책임: Run 실행 전에 origin·method·callback·secretRef·host·bounded DNS·port·도달성을
 * 확인해 "왜 Run이 실패하는가"를 사전에 분리한다. 실제 업무 Scenario는 실행하지 않는다.</p>
 * <p>핵심 불변식: secret 값은 응답·message·check에 포함하지 않고, DNS와 host 정책이
 * 실패하면 실제 HTTP 연결을 시도하지 않는다.</p>
 */
@Service
public class ProfileVerifier {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final NetworkSafety networkSafety;
    private final TargetHeaderResolver targetHeaderResolver;
    private final HttpClient httpClient;

    /**
     * 운영용 bounded network 정책과 runtime secret resolver로 검증기를 구성한다.
     *
     * @param networkSafety bounded DNS·SSRF host 검사
     * @param targetHeaderResolver 실제 실행과 같은 header·secretRef 검증 경계
     */
    @Autowired
    public ProfileVerifier(NetworkSafety networkSafety, TargetHeaderResolver targetHeaderResolver) {
        this(networkSafety, targetHeaderResolver, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * 테스트 가능한 HTTP client로 검증기를 구성한다.
     *
     * @param networkSafety bounded DNS·SSRF host 검사
     * @param targetHeaderResolver 실제 실행과 같은 header·secretRef 검증 경계
     * @param httpClient base origin 도달성을 확인할 client
     */
    ProfileVerifier(NetworkSafety networkSafety, TargetHeaderResolver targetHeaderResolver,
                    HttpClient httpClient) {
        this.networkSafety = java.util.Objects.requireNonNull(networkSafety);
        this.targetHeaderResolver = java.util.Objects.requireNonNull(targetHeaderResolver);
        this.httpClient = java.util.Objects.requireNonNull(httpClient);
    }

    /**
     * Profile을 검증하고 비민감 항목별 결과를 반환한다.
     *
     * <p>검증 순서: origin → method/callback → secretRef 존재 → host/port → bounded DNS·
     * 주소 안전성 → 도달성. 앞 단계가 실패하면 외부 연결을 시도하지 않는다.</p>
     *
     * @param profile 검증할 Target Profile
     * @return 항목별 통과 여부와 전체 통과 여부
     */
    public Result verify(TargetProfile profile) {
        Map<String, Object> checks = new LinkedHashMap<>();

        URI uri;
        try {
            uri = URI.create(profile.baseUri());
            boolean uriParsed = TargetProfileUriPolicy.isSafe(profile.baseUri());
            checks.put("uriParsed", uriParsed);
            if (!uriParsed) {
                return failed(checks, "baseUri는 HTTP(S) origin이어야 합니다.");
            }
        } catch (RuntimeException exception) {
            checks.put("uriParsed", false);
            return failed(checks, "baseUri를 파싱할 수 없습니다.");
        }

        boolean methodsConfigured = !profile.allowedMethods().isEmpty();
        checks.put("methodsConfigured", methodsConfigured);
        if (!methodsConfigured) {
            return failed(checks, "allowedMethods가 비어 있습니다.");
        }

        List<String> callbackNames = new ArrayList<>(new TreeSet<>(profile.callbackPaths().keySet()));
        checks.put("callbackPaths", callbackNames);
        boolean callbacksAllowed = profile.callbackPaths().values().stream()
                .allMatch(profile::isPathAllowed);
        checks.put("callbackPathsAllowed", callbacksAllowed);
        if (!callbacksAllowed) {
            return failed(checks, "callbackPaths가 allowedPaths 경계 밖에 있습니다.");
        }
        boolean callbackMethodAllowed = callbackNames.isEmpty() || profile.isMethodAllowed("POST");
        checks.put("callbackMethodAllowed", callbackMethodAllowed);
        if (!callbackMethodAllowed) {
            return failed(checks, "Callback 전달에는 POST method 허용이 필요합니다.");
        }

        List<String> secretRefs = new ArrayList<>(new TreeSet<>(profile.secretHeaders().values()));
        checks.put("secretRefs", secretRefs);
        try {
            // 실제 Run과 동일한 병합 경계로 default·secret header 전체를 값 비노출 상태에서 검증한다.
            targetHeaderResolver.resolve(profile, Map.of());
            checks.put("secretsConfigured", true);
            checks.put("headersConfigured", true);
        } catch (SecretResolutionException exception) {
            checks.put("secretsConfigured", false);
            checks.put("headersConfigured", false);
            return failed(checks, "필수 Target secretRef가 설정되지 않았습니다.");
        } catch (TargetHeaderResolutionException exception) {
            checks.put("secretsConfigured", false);
            checks.put("headersConfigured", false);
            return failed(checks, "Target header 계약이 안전하지 않습니다.");
        }

        boolean hostAllowed = profile.isOriginHostAllowed(uri);
        checks.put("hostAllowed", hostAllowed);
        int port = effectivePort(uri);
        boolean portAllowed = profile.allowedPorts().isEmpty() || profile.allowedPorts().contains(port);
        checks.put("portAllowed", portAllowed);
        if (!hostAllowed || !portAllowed) {
            return failed(checks, "baseUri host 또는 port가 Profile allowlist와 다릅니다.");
        }

        NetworkSafety.Assessment network = networkSafety.assess(uri);
        checks.put("dnsResolved", network.resolved());
        checks.put("hostSafe", network.safe());
        if (!network.resolved()) {
            return failed(checks, "DNS 해석에 실패했거나 제한 시간을 초과했습니다.");
        }
        if (!network.safe()) {
            return failed(checks, "허용되지 않은 host입니다 (metadata·link-local 등 차단).");
        }

        boolean reachable = probeReachable(uri);
        checks.put("reachable", reachable);
        if (!reachable) {
            return failed(checks, "base origin에 도달할 수 없습니다.");
        }
        return new Result(true, Map.copyOf(checks), "검증 통과");
    }

    /** base URI의 scheme 기본값을 반영한 실제 port를 반환한다. */
    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** base origin에 어떤 HTTP 응답이든 도착하는지 redirect 없이 확인한다. */
    private boolean probeReachable(URI baseUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() > 0;
        } catch (Exception exception) {
            return false;
        }
    }

    /** 실패 시점까지의 비민감 check를 불변 결과로 만든다. */
    private Result failed(Map<String, Object> checks, String message) {
        return new Result(false, Map.copyOf(checks), message);
    }

    /**
     * 검증 결과다.
     *
     * @param passed 전체 통과 여부
     * @param checks 항목별 비민감 통과 여부
     * @param message secret 값이 없는 사람 읽는 결과 메시지
     */
    public record Result(boolean passed, Map<String, Object> checks, String message) {
    }
}
