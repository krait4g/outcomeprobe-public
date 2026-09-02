package io.outcomeprobe.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * CLI HTTP client의 공통 Bearer 인증 경계를 검증한다.
 *
 * <p>검출 가능한 결함: JSON·raw·bundle 중 일부 요청이 Authorization header를 빠뜨리거나,
 * 401 본문과 redirect를 통해 token 원문을 외부로 전달하는 문제.</p>
 */
class ServerClientAuthorizationTest {

    private static final String TOKEN = "control-api-token-abcdefghijklmnopqrstuvwxyz012345";

    /**
     * JSON POST·JSON GET·raw GET·bundle 다운로드가 모두 같은 Bearer header를 사용해야 한다.
     *
     * @throws Exception loopback server 처리에 실패한 경우
     */
    @Test
    void sendsAuthorizationForEveryRequestFamily() throws Exception {
        List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer server = startServer(exchange -> {
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getRawPath().endsWith("/bundle")) {
                respond(exchange, 200, new byte[]{1, 2, 3});
            } else if (exchange.getRequestURI().getRawPath().endsWith("/reports/html")) {
                respond(exchange, 200, "<html></html>".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
            }
        });
        try {
            ServerClient client = new ServerClient(baseUrl(server), TOKEN);
            client.validate("kind: Scenario");
            client.createRun("{}");
            client.verifyTargetProfile("realtime-track-compose");
            client.getRun("run-1");
            client.getReportHtml("run-1");
            assertThat(client.getBundle("run-1")).containsExactly(1, 2, 3);

            assertThat(requests).extracting(RecordedRequest::authorization)
                    .containsOnly("Bearer " + TOKEN);
            assertThat(requests).extracting(RecordedRequest::method)
                    .containsExactly("POST", "POST", "POST", "GET", "GET", "GET");
            assertThat(requests).extracting(RecordedRequest::path)
                    .containsExactly(
                            "/api/v1/artifacts/validate",
                            "/api/v1/runs",
                            "/api/v1/target-profiles/realtime-track-compose/verify",
                            "/api/v1/runs/run-1",
                            "/api/v1/runs/run-1/reports/html",
                            "/api/v1/runs/run-1/bundle");
            assertThat(requests.get(0).idempotencyKey()).isNull();
            assertThat(requests.get(1).idempotencyKey())
                    .matches("op-cli-[0-9a-f-]{36}");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 401 응답이 token을 반사해도 예외 message와 body에는 원문이 없어야 한다.
     *
     * @throws Exception loopback server 처리에 실패한 경우
     */
    @Test
    void unauthorizedResponseUsesSafeMessageAndRedactedBody() throws Exception {
        HttpServer server = startServer(exchange -> respond(exchange, 401,
                ("{\"message\":\"Bearer " + TOKEN + "\"}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            ServerClient client = new ServerClient(baseUrl(server), TOKEN);

            assertThatThrownBy(client::listRuns)
                    .isInstanceOf(ServerClient.CliRequestException.class)
                    .hasMessageContaining("인증에 실패")
                    .hasMessageNotContaining(TOKEN)
                    .satisfies(exception -> {
                        ServerClient.CliRequestException requestException =
                                (ServerClient.CliRequestException) exception;
                        assertThat(requestException.statusCode()).isEqualTo(401);
                        assertThat(requestException.body().toString()).doesNotContain(TOKEN);
                    });
        } finally {
            server.stop(0);
        }
    }

    /**
     * JSON escape로 쪼개 반사한 token도 parsing 뒤 CLI 결과에 복원되면 안 된다.
     *
     * @throws Exception loopback server 처리에 실패한 경우
     */
    @Test
    void redactsTokenAfterJsonEscapeDecoding() throws Exception {
        String escapedToken = TOKEN.replace("-", "\\u002d");
        HttpServer server = startServer(exchange -> respond(exchange, 200,
                ("{\"echo\":\"" + escapedToken + "\"}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            ServerClient client = new ServerClient(baseUrl(server), TOKEN);

            assertThat(client.listRuns().toString())
                    .contains("<redacted>")
                    .doesNotContain(TOKEN);
        } finally {
            server.stop(0);
        }
    }

    /**
     * redirect를 자동 추적해 Bearer header를 다른 origin으로 전달하지 않아야 한다.
     *
     * @throws Exception loopback server 처리에 실패한 경우
     */
    @Test
    void doesNotForwardAuthorizationAcrossRedirect() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer destination = startServer(exchange -> {
            redirectedRequests.incrementAndGet();
            respond(exchange, 200, "unexpected".getBytes(StandardCharsets.UTF_8));
        });
        HttpServer source = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl(destination) + "/destination");
            respond(exchange, 302, new byte[0]);
        });
        try {
            ServerClient client = new ServerClient(baseUrl(source), TOKEN);
            assertThatThrownBy(() -> client.getRaw("/redirect"))
                    .isInstanceOf(ServerClient.CliRequestException.class)
                    .hasMessageContaining("302")
                    .hasMessageNotContaining(TOKEN);
            assertThat(redirectedRequests).hasValue(0);
        } finally {
            source.stop(0);
            destination.stop(0);
        }
    }

    /**
     * 임의 loopback port에서 테스트 HTTP server를 시작한다.
     *
     * @param handler 요청 처리기
     * @return 시작된 HTTP server
     * @throws IOException server 생성에 실패한 경우
     */
    private HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    /**
     * 테스트 server의 loopback base URL을 만든다.
     *
     * @param server 실행 중인 테스트 server
     * @return HTTP base URL
     */
    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 지정 상태와 body를 쓴 뒤 exchange를 닫는다.
     *
     * @param exchange 현재 HTTP 교환
     * @param status 응답 상태 코드
     * @param body 응답 바이트
     * @throws IOException 응답 전송에 실패한 경우
     */
    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /**
     * client가 전송한 인증 관련 요청 증거다.
     *
     * @param method HTTP method
     * @param path 요청 path
     * @param authorization Authorization header
     * @param idempotencyKey 변경 요청의 intent header, 없으면 null
     */
    private record RecordedRequest(
            String method, String path, String authorization, String idempotencyKey) {
    }
}
