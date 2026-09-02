package io.outcomeprobe.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.outcomeprobe.infrastructure.net.NetworkSafety;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ProfileVerifier의 연결·안전성 검증 논리를 검증한다.
 *
 * <p>검출 가능한 결함: host·method·callback·secretRef 경계를 생략하거나 DNS 실패 뒤
 * 외부 연결을 시도하는 문제, secret 원문을 검증 결과에 노출하는 문제.</p>
 */
class ProfileVerifierTest {

    private final NetworkSafety networkSafety = new NetworkSafety();

    /** 테스트마다 bounded DNS executor를 종료한다. */
    @AfterEach
    void shutdownNetworkSafety() {
        networkSafety.shutdown();
    }

    /** 테스트용 Target Profile을 만든다. */
    private TargetProfile profile(String baseUri, List<String> allowedPaths,
                                  List<String> allowedMethods, List<String> allowedHosts,
                                  List<Integer> allowedPorts, Map<String, String> secretHeaders,
                                  Map<String, String> callbackPaths) {
        return new TargetProfile("test", baseUri, allowedPaths, allowedMethods,
                allowedHosts, allowedPorts, Map.of(), secretHeaders, callbackPaths,
                List.of(), List.of());
    }

    /** secret이 필요 없는 기본 검증기를 만든다. */
    private ProfileVerifier verifier() {
        return new ProfileVerifier(networkSafety,
                new TargetHeaderResolver(ignored -> Optional.empty()));
    }

    /** 허용되지 않은 port는 DNS·도달성 검사 전에 차단된다. */
    @Test
    void rejectsDisallowedPort() {
        TargetProfile profile = profile("http://localhost:8080", List.of("/**"),
                List.of("GET", "POST"), List.of(), List.of(9999), Map.of(), Map.of());

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks().get("hostAllowed")).isEqualTo(true);
        assertThat(result.checks().get("portAllowed")).isEqualTo(false);
        assertThat(result.checks()).doesNotContainKey("dnsResolved");
    }

    /** base origin과 맞지 않는 host allowlist는 DNS 전에 차단된다. */
    @Test
    void rejectsHostOutsideAllowlist() {
        TargetProfile profile = profile("http://127.0.0.1:65534", List.of("/**"),
                List.of("GET"), List.of("example.test"), List.of(), Map.of(), Map.of());

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks().get("hostAllowed")).isEqualTo(false);
        assertThat(result.checks()).doesNotContainKey("dnsResolved");
    }

    /** import 이전 legacy 모델이라도 method 목록이 비어 있으면 검증은 fail-closed한다. */
    @Test
    void rejectsMissingAllowedMethods() {
        TargetProfile profile = profile("http://127.0.0.1:65534", List.of("/**"),
                List.of(), List.of(), List.of(), Map.of(), Map.of());

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks().get("methodsConfigured")).isEqualTo(false);
    }

    /** callback 경로는 일반 요청 path allowlist 안에 있어야 한다. */
    @Test
    void rejectsCallbackOutsideAllowedPaths() {
        TargetProfile profile = profile("http://127.0.0.1:65534", List.of("/api/**"),
                List.of("GET", "POST"), List.of(), List.of(), Map.of(),
                Map.of("payment", "/admin/callback"));

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks().get("callbackPathsAllowed")).isEqualTo(false);
    }

    /** callback을 선언한 Profile은 POST method를 명시적으로 허용해야 한다. */
    @Test
    void rejectsCallbackWithoutPostMethod() {
        TargetProfile profile = profile("http://127.0.0.1:65534", List.of("/api/**"),
                List.of("GET"), List.of(), List.of(), Map.of(),
                Map.of("payment", "/api/callback"));

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks().get("callbackMethodAllowed")).isEqualTo(false);
    }

    /** 누락된 secretRef는 실제 값이나 외부 요청 없이 거부된다. */
    @Test
    void rejectsMissingSecretReference() {
        TargetProfile profile = profile("http://127.0.0.1:65534", List.of("/**"),
                List.of("GET"), List.of(), List.of(),
                Map.of("Authorization", "target-auth-token"), Map.of());

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks().get("secretsConfigured")).isEqualTo(false);
        assertThat(result.checks()).doesNotContainKey("dnsResolved");
    }

    /** bounded DNS가 실패하면 주소나 HTTP 요청을 노출하지 않고 즉시 중단한다. */
    @Test
    void rejectsBoundedDnsFailureBeforeReachability() {
        NetworkSafety safety = org.mockito.Mockito.mock(NetworkSafety.class);
        when(safety.assess(URI.create("http://dns-timeout.test:8080")))
                .thenReturn(new NetworkSafety.Assessment(false, false));
        ProfileVerifier verifier = new ProfileVerifier(safety,
                new TargetHeaderResolver(ignored -> Optional.empty()));
        TargetProfile profile = profile("http://dns-timeout.test:8080", List.of("/**"),
                List.of("GET"), List.of(), List.of(), Map.of(), Map.of());

        ProfileVerifier.Result result = verifier.verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).containsEntry("dnsResolved", false)
                .doesNotContainKey("reachable");
        verify(safety).assess(URI.create("http://dns-timeout.test:8080"));
    }

    /** metadata(link-local) host는 안전성 검사에서 차단된다. */
    @Test
    void rejectsMetadataHost() {
        TargetProfile profile = profile("http://169.254.169.254", List.of("/**"),
                List.of("GET"), List.of(), List.of(), Map.of(), Map.of());

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).containsEntry("dnsResolved", true)
                .containsEntry("hostSafe", false)
                .doesNotContainKey("reachable");
    }

    /** 실제 Run이 거부할 JDK 제한 default header도 verifier에서 false-pass하지 않아야 한다. */
    @Test
    void rejectsUnsafeDefaultHeaderSemanticsBeforeNetwork() {
        TargetProfile profile = new TargetProfile("test", "http://127.0.0.1:65534",
                List.of("/**"), List.of("GET"), List.of(), List.of(),
                Map.of("Host", "spoofed.example.test"), Map.of(), Map.of(), List.of(), List.of());

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).containsEntry("headersConfigured", false)
                .doesNotContainKey("dnsResolved");
        assertThat(result.toString()).doesNotContain("spoofed.example.test");
    }

    /** 대소문자 중복 default header는 순서 의존 병합 없이 사전 거부돼야 한다. */
    @Test
    void rejectsCaseInsensitiveHeaderCollision() {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("X-Tenant", "first-private-value");
        headers.put("x-tenant", "second-private-value");
        TargetProfile profile = new TargetProfile("test", "http://127.0.0.1:65534",
                List.of("/**"), List.of("GET"), List.of(), List.of(), headers,
                Map.of(), Map.of(), List.of(), List.of());

        ProfileVerifier.Result result = verifier().verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).containsEntry("headersConfigured", false);
        assertThat(result.toString()).doesNotContain("private-value");
    }

    /** custom secret resolver의 CR/LF 값은 값 자체를 응답에 넣지 않고 거부해야 한다. */
    @Test
    void rejectsUnsafeResolvedSecretValueWithoutExposure() {
        String secretValue = "safe\r\nX-Leak: never-print-secret";
        TargetProfile profile = profile("http://127.0.0.1:65534", List.of("/**"),
                List.of("GET"), List.of(), List.of(),
                Map.of("Authorization", "target-auth-token"), Map.of());

        ProfileVerifier.Result result = new ProfileVerifier(networkSafety,
                new TargetHeaderResolver(ignored -> Optional.of(secretValue))).verify(profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).containsEntry("secretsConfigured", false)
                .containsEntry("headersConfigured", false)
                .doesNotContainKey("dnsResolved");
        assertThat(result.toString()).doesNotContain("never-print-secret");
    }

    /** 모든 preflight가 통과하면 origin 도달성을 확인하되 secret 값은 결과에 남기지 않는다. */
    @Test
    void passesCompletePreflightWithoutExposingSecretValue() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String secretValue = "never-print-this-secret-value";
        TargetProfile profile = profile("http://127.0.0.1:" + port, List.of("/**"),
                List.of("GET", "POST"), List.of("127.0.0.1"), List.of(port),
                Map.of("Authorization", "target-auth-token"),
                Map.of("payment", "/callbacks/payment"));

        try {
            ProfileVerifier.Result result = new ProfileVerifier(
                    networkSafety,
                    new TargetHeaderResolver(ignored -> Optional.of(secretValue))).verify(profile);

            assertThat(result.passed()).isTrue();
            assertThat(result.checks()).containsEntry("methodsConfigured", true)
                    .containsEntry("callbackPathsAllowed", true)
                    .containsEntry("callbackMethodAllowed", true)
                    .containsEntry("secretsConfigured", true)
                    .containsEntry("headersConfigured", true)
                    .containsEntry("hostAllowed", true)
                    .containsEntry("portAllowed", true)
                    .containsEntry("dnsResolved", true)
                    .containsEntry("hostSafe", true)
                    .containsEntry("reachable", true);
            assertThat(result.toString()).doesNotContain(secretValue);
        } finally {
            server.stop(0);
        }
    }
}
