package io.outcomeprobe.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@link OutcomeProbeCli}의 종료 코드 계약을 검증한다.
 *
 * <p>검출 가능한 결함: help·version 요청이 비정상 종료 코드를 반환해 CI가
 * 단순 사용법 확인을 실패로 오해하는 문제.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class OutcomeProbeCliTest {

    private static final String CONTROL_API_TOKEN =
            "cli-control-api-token-abcdefghijklmnopqrstuvwxyz012345";

    /**
     * help 요청은 정상 종료 코드를 반환해야 CI 스크립트가 안전하다.
     */
    @Test
    void helpReturnsZero() {
        assertThat(OutcomeProbeCli.execute("--help")).isZero();
    }

    /**
     * version 요청은 정상 종료 코드를 반환해야 doctor 단계가 버전 비교를 할 수 있다.
     */
    @Test
    void versionReturnsZero() {
        assertThat(OutcomeProbeCli.execute("--version")).isZero();
    }

    /**
     * 하위 명령 없는 실행은 사용법을 보여 주고 정상 종료한다.
     */
    @Test
    void bareInvocationShowsUsageAndReturnsZero() {
        assertThat(OutcomeProbeCli.execute()).isZero();
    }

    /**
     * 모든 공개 하위 명령의 help 요청은 입력 오류로 분류되지 않고 정상 종료해야 한다.
     */
    @Test
    void everyPublicSubcommandHelpReturnsZero() {
        for (String command : List.of("run", "run-file", "import", "validate", "doctor",
                "list", "report", "bundle", "cancel")) {
            assertThat(OutcomeProbeCli.execute(command, "--help"))
                    .as("%s --help exit code", command)
                    .isZero();
        }
    }

    /**
     * core-only help는 단일 YAML import만 안내하고 디렉터리 배포 기능을 광고하지 않는다.
     *
     * @param output 표준 출력과 오류 캡처
     */
    @Test
    void importHelpOnlyDescribesSingleYamlFile(CapturedOutput output) {
        assertThat(OutcomeProbeCli.execute("import", "--help")).isZero();
        assertThat(output.getOut())
                .contains("Artifact YAML 파일")
                .doesNotContainIgnoringCase("Scenario Pack")
                .doesNotContain("Pack 디렉터리")
                .doesNotContain("pack.yaml");
    }

    /**
     * 디렉터리는 서버 연결 전에 입력 오류로 거부한다.
     *
     * @param directory JUnit이 제공하는 임시 디렉터리
     * @param output 표준 출력과 오류 캡처
     */
    @Test
    void directoryImportFailsBeforeNetwork(@TempDir Path directory, CapturedOutput output) {
        int exit = OutcomeProbeCli.execute("import", directory.toString(),
                "--server", "http://127.0.0.1:1");

        assertThat(exit).isEqualTo(ExitCodes.INPUT_ERROR);
        assertThat(output.getErr()).contains("Artifact YAML 파일을 찾을 수 없습니다");
    }

    /**
     * 명시 credential 옵션은 token 원문이 아니라 파일 경로만 받아야 한다.
     *
     * @param output 표준 출력과 오류 캡처
     */
    @Test
    void helpOffersTokenFileButNoRawTokenOption(CapturedOutput output) {
        assertThat(OutcomeProbeCli.execute("import", "--help")).isZero();

        assertThat(output.getOut())
                .contains("--token-file")
                .doesNotContain("--token=")
                .doesNotContain("--token ");
    }

    /**
     * 누락되거나 읽을 수 없는 token은 네트워크 전에 비정상 종료와 안전한 설명을 남긴다.
     *
     * @param directory JUnit이 제공하는 격리 디렉터리
     * @param output 표준 출력과 오류 캡처
     */
    @Test
    void missingTokenFileFailsBeforeNetwork(@TempDir Path directory, CapturedOutput output) {
        Path missing = directory.resolve("missing-control-api-token");

        int exit = OutcomeProbeCli.execute("list", "runs",
                "--server", "http://127.0.0.1:1",
                "--token-file", missing.toString());

        assertThat(exit).isEqualTo(ExitCodes.INPUT_ERROR);
        assertThat(output.getErr())
                .contains("CLI 설정 오류")
                .doesNotContain("Authorization")
                .doesNotContain("Bearer ");
    }

    /**
     * doctor의 모든 요청은 Bearer header를 쓰되 성공 body가 token을 반사해도 출력하지 않는다.
     *
     * @param directory JUnit이 제공하는 격리 디렉터리
     * @param output 표준 출력과 오류 캡처
     * @throws Exception fixture 파일이나 loopback server 처리가 실패한 경우
     */
    @Test
    void doctorAuthenticatesEveryRequestWithoutPrintingToken(
            @TempDir Path directory, CapturedOutput output) throws Exception {
        Path tokenFile = writeTokenFile(directory);
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer server = startServer(exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getRawPath();
            String response = path.startsWith("/api/v1/artifacts")
                    ? "[]" : "{\"status\":\"UP\",\"echo\":\"" + CONTROL_API_TOKEN + "\"}";
            respond(exchange, 200, response);
        });
        try {
            int exit = OutcomeProbeCli.execute("doctor",
                    "--server", baseUrl(server),
                    "--token-file", tokenFile.toString());

            assertThat(exit).isZero();
            assertThat(authorizations).hasSize(4).containsOnly("Bearer " + CONTROL_API_TOKEN);
            assertThat(output.getOut() + output.getErr())
                    .contains("Control API Bearer token 설정됨")
                    .doesNotContain(CONTROL_API_TOKEN);
        } finally {
            server.stop(0);
        }
    }

    /**
     * doctor의 명시적 Target 검사는 서버 사전검사를 호출하고 성공 결과만 준비 완료로 보고한다.
     *
     * @param directory JUnit이 제공하는 격리 디렉터리
     * @param output 표준 출력과 오류 캡처
     * @throws Exception fixture 파일이나 loopback server 처리가 실패한 경우
     */
    @Test
    void doctorVerifiesExplicitTargetWithoutPrintingSecret(
            @TempDir Path directory, CapturedOutput output) throws Exception {
        Path tokenFile = writeTokenFile(directory);
        String targetSecret = "target-secret-should-never-be-printed";
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = startServer(exchange -> {
            paths.add(exchange.getRequestURI().getRawPath());
            String path = exchange.getRequestURI().getRawPath();
            String response;
            if (path.endsWith("/verify")) {
                response = "{\"passed\":true,\"checks\":{\"secretRefsResolved\":true}}";
            } else if (path.startsWith("/api/v1/artifacts")) {
                response = "[]";
            } else {
                response = "{\"status\":\"UP\"}";
            }
            respond(exchange, 200, response);
        });
        try {
            int exit = OutcomeProbeCli.execute("doctor",
                    "--server", baseUrl(server),
                    "--token-file", tokenFile.toString(),
                    "--target", "realtime-track-compose");

            assertThat(exit).isZero();
            assertThat(paths).contains("/api/v1/target-profiles/realtime-track-compose/verify");
            assertThat(output.getOut() + output.getErr())
                    .contains("Target Profile 연결 사전검사")
                    .doesNotContain(targetSecret)
                    .doesNotContain(CONTROL_API_TOKEN);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 사전검사가 실패하면 doctor가 성공으로 끝나지 않아 잘못된 연결을 CI가 놓치지 않는다.
     *
     * @param directory JUnit이 제공하는 격리 디렉터리
     * @param output 표준 출력과 오류 캡처
     * @throws Exception fixture 파일이나 loopback server 처리가 실패한 경우
     */
    @Test
    void doctorFailsClosedWhenTargetPreflightFails(
            @TempDir Path directory, CapturedOutput output) throws Exception {
        Path tokenFile = writeTokenFile(directory);
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String response = path.endsWith("/verify")
                    ? "{\"passed\":false,\"checks\":{\"secretRefsResolved\":false}}"
                    : path.startsWith("/api/v1/artifacts") ? "[]" : "{\"status\":\"UP\"}";
            respond(exchange, 200, response);
        });
        try {
            int exit = OutcomeProbeCli.execute("doctor",
                    "--server", baseUrl(server),
                    "--token-file", tokenFile.toString(),
                    "--target", "realtime-track-compose");

            assertThat(exit).isEqualTo(ExitCodes.SERVER_ERROR);
            assertThat(output.getErr()).contains("연결 사전검사가 실패했습니다");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 401 본문이 token을 반사해도 CLI는 고정 인증 오류만 출력하고 비정상 종료해야 한다.
     *
     * @param directory JUnit이 제공하는 격리 디렉터리
     * @param output 표준 출력과 오류 캡처
     * @throws Exception fixture 파일이나 loopback server 처리가 실패한 경우
     */
    @Test
    void unauthorizedCliResponseIsNonzeroAndDoesNotPrintToken(
            @TempDir Path directory, CapturedOutput output) throws Exception {
        Path tokenFile = writeTokenFile(directory);
        HttpServer server = startServer(exchange -> respond(exchange, 401,
                "{\"message\":\"Bearer " + CONTROL_API_TOKEN + "\"}"));
        try {
            int exit = OutcomeProbeCli.execute("list", "runs",
                    "--server", baseUrl(server),
                    "--token-file", tokenFile.toString());

            assertThat(exit).isNotZero();
            assertThat(output.getOut() + output.getErr())
                    .contains("인증에 실패")
                    .doesNotContain(CONTROL_API_TOKEN);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 테스트용 token 파일을 UTF-8 단일행으로 만든다.
     *
     * @param directory JUnit이 제공한 격리 디렉터리
     * @return 생성한 token 파일
     * @throws IOException 파일 쓰기가 실패한 경우
     */
    private Path writeTokenFile(Path directory) throws IOException {
        Path tokenFile = directory.resolve("control-api-token");
        Files.writeString(tokenFile, CONTROL_API_TOKEN + "\n", StandardCharsets.UTF_8);
        return tokenFile;
    }

    /**
     * 임의 loopback port에서 CLI 테스트 server를 시작한다.
     *
     * @param handler 요청 처리기
     * @return 시작된 server
     * @throws IOException server 생성에 실패한 경우
     */
    private HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    /**
     * loopback server의 base URL을 반환한다.
     *
     * @param server 실행 중인 server
     * @return HTTP base URL
     */
    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 문자열 응답을 쓰고 HTTP exchange를 닫는다.
     *
     * @param exchange 현재 HTTP 교환
     * @param status HTTP 상태 코드
     * @param body UTF-8 응답 본문
     * @throws IOException 응답 전송에 실패한 경우
     */
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
