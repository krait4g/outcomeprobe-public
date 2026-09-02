package io.outcomeprobe.infrastructure.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.outcomeprobe.execution.TargetHttpPort;
import io.outcomeprobe.execution.adapter.out.HttpClientTargetAdapter;
import io.outcomeprobe.profile.TargetProfile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * SSRF 방어와 network policy 강제를 검증한다 (OP-AUDIT-SEC-001).
 *
 * <p>검증 원칙: metadata(link-local)·multicast·unspecified·IPv4-mapped IPv6 우회를
 * 차단하고, redirect를 따라가지 않으며, Profile에 허용되지 않은 host는 거부한다.</p>
 */
class SsrfProtectionTest {

    private final NetworkSafety networkSafety = new NetworkSafety();

    /**
     * 클라우드 metadata 주소를 포함한 금지 주소는 차단된다.
     */
    @Test
    void blocksMetadataAndSpecialAddresses() {
        assertThat(networkSafety.isSafe(URI.create("http://169.254.169.254/latest/meta-data"))).isFalse();
        assertThat(networkSafety.isSafe(URI.create("http://[fe80::1]/x"))).isFalse();
        assertThat(networkSafety.isSafe(URI.create("http://224.0.0.1/x"))).isFalse();
        assertThat(networkSafety.isSafe(URI.create("http://0.0.0.0/x"))).isFalse();
    }

    /**
     * IPv4-mapped IPv6로 metadata 주소를 우회하려는 시도는 차단된다.
     */
    @Test
    void blocksIpv4MappedIpv6Bypass() {
        assertThat(networkSafety.isSafe(URI.create("http://[::ffff:169.254.169.254]/x"))).isFalse();
    }

    /**
     * loopback·private 주소는 local 도구 특성상 허용된다.
     */
    @Test
    void allowsLocalAddresses() {
        assertThat(networkSafety.isSafe(URI.create("http://localhost:8091/x"))).isTrue();
        assertThat(networkSafety.isSafe(URI.create("http://127.0.0.1:8091/x"))).isTrue();
    }

    /**
     * Profile의 allowedHosts에 없는 host는 adapter에서 거부된다.
     *
     * @throws Exception 서버 기동 실패 시 발생한다
     */
    @Test
    void adapterRejectsDisallowedHost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        try {
            HttpClientTargetAdapter adapter = new HttpClientTargetAdapter(networkSafety, new ObjectMapper());
            TargetProfile profile = new TargetProfile("restricted", "http://127.0.0.1:" + port,
                    List.of("/**"), List.of("GET", "POST"),
                    List.of("allowed.example.com"), List.of(),
                    Map.of(), Map.of(), Map.of(), List.of(), List.of());

            TargetHttpPort.TargetHttpResponse response =
                    adapter.call(profile, "GET", "/x", Map.of(), null);

            assertThat(response.failureCode()).isEqualTo("TARGET_HOST_NOT_ALLOWED");
        } finally {
            server.stop(0);
        }
    }

    /** JDK 제한 header가 남아도 adapter는 예외를 누출하지 않고 typed 실패로 바꾼다. */
    @Test
    void adapterConvertsInvalidHeaderToTypedFailure() {
        HttpClientTargetAdapter adapter = new HttpClientTargetAdapter(networkSafety, new ObjectMapper());
        TargetProfile profile = new TargetProfile("open", "http://127.0.0.1:1",
                List.of("/**"), List.of("GET", "POST"), List.of(), List.of(),
                Map.of(), Map.of(), Map.of(), List.of(), List.of());

        TargetHttpPort.TargetHttpResponse response =
                adapter.call(profile, "GET", "/x", Map.of("Content-Length", "1"), null);

        assertThat(response.failureCode()).isEqualTo("TARGET_HEADER_INVALID");
        assertThat(response.reached()).isFalse();
    }

    /**
     * 허용 host의 302 redirect가 metadata로 향해도 따라가지 않는다.
     *
     * @throws Exception 서버 기동 실패 시 발생한다
     */
    @Test
    void doesNotFollowRedirectToMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            HttpClientTargetAdapter adapter = new HttpClientTargetAdapter(networkSafety, new ObjectMapper());
            TargetProfile profile = new TargetProfile("open", "http://127.0.0.1:" + port,
                    List.of("/**"), List.of("GET", "POST"),
                    List.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());

            TargetHttpPort.TargetHttpResponse response =
                    adapter.call(profile, "GET", "/redirect", Map.of(), null);

            // redirect를 따라가지 않고 302에서 멈춘다. metadata 요청은 발생하지 않는다.
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.failureCode()).isEqualTo("TARGET_REDIRECT_REJECTED");
        } finally {
            server.stop(0);
        }
    }
}
