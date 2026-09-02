package io.outcomeprobe.cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * OutcomeProbe Control API와 통신하는 CLI 전용 client다.
 *
 * <p>책임: Artifact import·Run 생성·상태 조회·report 조회를 단순화해 명령 구현이
 * HTTP 세부 사항에 묻히지 않게 한다.</p>
 * <p>핵심 불변식: 모든 Control API·report·bundle 요청은 같은 검증된 Bearer token을
 * 사용하며 redirect를 따라 다른 origin으로 credential을 전달하지 않는다.</p>
 */
public class ServerClient {

    private final String baseUrl;
    private final String controlApiToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * server base URL과 검증된 Control API token을 주입한다.
     *
     * @param baseUrl Control API base URL
     * @param controlApiToken {@link CliConnectionOptions}가 검증한 Bearer token
     */
    ServerClient(String baseUrl, String controlApiToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.controlApiToken = Objects.requireNonNull(controlApiToken, "controlApiToken");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 인증 header가 다른 origin으로 자동 전달되지 않게 redirect를 따르지 않는다.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * YAML 문서를 서버 validation core로 검증한다.
     *
     * @param yaml 원본 YAML 문자열
     * @return 검증 결과 JSON
     * @throws CliRequestException 검증 실패·서버 오류 시 발생한다
     */
    public JsonNode validate(String yaml) {
        return post("/api/v1/artifacts/validate", yaml, "text/plain");
    }

    /**
     * YAML 문서를 import한다.
     *
     * @param yaml 원본 YAML 문자열
     * @return 응답 JSON
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode importArtifact(String yaml) {
        return post("/api/v1/artifacts/import", yaml, "text/plain");
    }

    /**
     * Run 생성을 요청한다.
     *
     * @param requestBody Run 생성 요청 JSON
     * @return 응답 JSON
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode createRun(String requestBody) {
        return postMutation("/api/v1/runs", requestBody, "application/json",
                "op-cli-" + UUID.randomUUID());
    }

    /**
     * Run 상태를 조회한다.
     *
     * @param runId Run 식별자
     * @return 응답 JSON
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode getRun(String runId) {
        return get("/api/v1/runs/" + runId);
    }

    /**
     * JSON report를 조회한다.
     *
     * @param runId Run 식별자
     * @return report JSON
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode getReport(String runId) {
        return get("/api/v1/runs/" + runId + "/reports/json");
    }

    /**
     * Scenario artifact 목록을 조회한다.
     *
     * @return Scenario 요약 배열
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode listScenarios() {
        return get("/api/v1/artifacts?kind=SCENARIO");
    }

    /**
     * Target Profile artifact 목록을 조회한다.
     *
     * @return Target Profile 요약 배열
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode listProfiles() {
        return get("/api/v1/artifacts?kind=TARGET_PROFILE");
    }

    /**
     * 지정한 Target Profile의 연결 사전검사를 실행한다.
     *
     * <p>Profile 이름은 경로 조각으로 사용되므로 Artifact 이름 문법을 먼저 강제한다. 응답에는
     * secret 원문이 아니라 참조별 설정 여부만 포함돼야 하며, 공통 응답 redaction 경계를 그대로
     * 통과시킨다.</p>
     *
     * @param profileName 사전검사할 Target Profile 이름
     * @return 서버가 계산한 안전한 사전검사 결과
     * @throws IllegalArgumentException Profile 이름이 Artifact 이름 문법과 다르면 발생한다
     * @throws CliRequestException 연결 또는 사전검사 요청이 실패하면 발생한다
     */
    public JsonNode verifyTargetProfile(String profileName) {
        if (profileName == null
                || !profileName.matches("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$")) {
            throw new IllegalArgumentException("Target Profile 이름 형식이 올바르지 않습니다.");
        }
        return post("/api/v1/target-profiles/" + profileName + "/verify", "",
                "application/json");
    }

    /**
     * Run 목록을 조회한다.
     *
     * @return Run 요약 배열
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode listRuns() {
        return get("/api/v1/runs");
    }

    /**
     * HTML report를 원본 텍스트로 조회한다.
     *
     * @param runId Run 식별자
     * @return HTML 문자열
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public String getReportHtml(String runId) {
        return getRaw("/api/v1/runs/" + runId + "/reports/html");
    }

    /**
     * JUnit XML report를 원본 텍스트로 조회한다.
     *
     * @param runId Run 식별자
     * @return JUnit XML 문자열
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public String getReportJunit(String runId) {
        return getRaw("/api/v1/runs/" + runId + "/reports/junit");
    }

    /**
     * Run bundle(zip)을 바이트로 내려받는다.
     *
     * @param runId Run 식별자
     * @return zip 바이트
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public byte[] getBundle(String runId) {
        HttpRequest request = requestBuilder("/api/v1/runs/" + runId + "/bundle",
                Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (!isSuccessful(response.statusCode())) {
                throw requestFailure(response.statusCode(), null);
            }
            return response.body();
        } catch (CliRequestException e) {
            throw e;
        } catch (Exception e) {
            throw connectionFailure();
        }
    }

    /**
     * Run 취소를 요청한다.
     *
     * @param runId Run 식별자
     * @return 응답 JSON
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public JsonNode cancelRun(String runId) {
        return post("/api/v1/runs/" + runId + "/cancel", "", "application/json");
    }

    /**
     * 경로를 GET해 원본 텍스트 응답을 반환한다.
     *
     * @param path API 경로
     * @return 응답 본문 문자열
     * @throws CliRequestException 요청·서버 오류 시 발생한다
     */
    public String getRaw(String path) {
        HttpRequest request = requestBuilder(path, Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (!isSuccessful(response.statusCode())) {
                throw requestFailure(response.statusCode(), response.body());
            }
            return redactControlApiToken(response.body());
        } catch (CliRequestException e) {
            throw e;
        } catch (Exception e) {
            throw connectionFailure();
        }
    }

    /**
     * JSON을 POST하고 응답을 파싱한다. 4xx 이상은 예외로 던진다.
     */
    private JsonNode post(String path, String body, String contentType) {
        HttpRequest request = requestBuilder(path, Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    /**
     * GET 요청 후 JSON 응답을 파싱한다. 4xx 이상은 예외로 던진다.
     */
    private JsonNode get(String path) {
        HttpRequest request = requestBuilder(path, Duration.ofSeconds(30))
                .GET()
                .build();
        return send(request);
    }

    /**
     * 변경 요청 하나에 process 생존 범위의 고유 intent header를 결속한다.
     *
     * <p>이 메소드는 변경 요청을 자동 재시도하지 않는다. command process 종료 뒤 동일 key를
     * 복구하는 durable intent 저장소는 현재 CLI 계약에 포함되지 않는다.</p>
     *
     * @param path API 경로
     * @param body 요청 본문
     * @param contentType 요청 content type
     * @param idempotencyKey 한 번의 command intent key
     * @return 응답 JSON
     */
    private JsonNode postMutation(
            String path, String body, String contentType, String idempotencyKey) {
        HttpRequest request = requestBuilder(path, Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    /**
     * 모든 전송 형식이 공유하는 인증된 요청 builder를 만든다.
     *
     * <p>한 경로에서 header를 빠뜨리는 detector divergence를 막기 위해 JSON, raw text,
     * bundle 요청이 모두 이 메소드를 거친다.</p>
     *
     * @param path base URL 뒤에 붙일 API 경로
     * @param timeout 요청별 최대 대기 시간
     * @return Authorization header가 설정된 요청 builder
     */
    private HttpRequest.Builder requestBuilder(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + controlApiToken);
    }

    /**
     * 요청을 전송하고 JSON 응답을 파싱한다. 상태 코드가 400 이상이면 예외로 던진다.
     */
    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            String safeResponse = redactControlApiToken(response.body());
            if (!isSuccessful(response.statusCode())) {
                throw requestFailure(response.statusCode(), safeResponse);
            }
            JsonNode body = safeResponse == null || safeResponse.isBlank()
                    ? objectMapper.createObjectNode()
                    : parseRedactedJson(safeResponse);
            return body;
        } catch (CliRequestException e) {
            throw e;
        } catch (Exception e) {
            throw connectionFailure();
        }
    }

    /**
     * HTTP 실패를 token 원문이 없는 예외로 변환한다.
     *
     * <p>401·403은 서버가 보낸 설명을 신뢰하지 않고 고정 문구를 사용한다. 다른 problem
     * 본문도 현재 token 원문을 먼저 대체한 뒤에만 명령 계층으로 전달한다.</p>
     *
     * @param statusCode HTTP 상태 코드
     * @param responseBody 서버가 보낸 선택적 문자열 본문
     * @return 안전한 CLI 요청 예외
     */
    private CliRequestException requestFailure(int statusCode, String responseBody) {
        if (statusCode == 401) {
            return new CliRequestException(statusCode, objectMapper.createObjectNode()
                    .put("message", "Control API 인증에 실패했습니다. token 설정을 확인하세요."));
        }
        if (statusCode == 403) {
            return new CliRequestException(statusCode, objectMapper.createObjectNode()
                    .put("message", "Control API 접근이 거부되었습니다."));
        }
        String safeResponse = redactControlApiToken(responseBody);
        if (safeResponse != null && !safeResponse.isBlank()) {
            try {
                JsonNode parsed = parseRedactedJson(safeResponse);
                if (parsed != null && parsed.isObject()) {
                    return new CliRequestException(statusCode, parsed);
                }
            } catch (Exception ignored) {
                // 해석 불가능한 원격 본문은 출력하지 않고 상태 코드만 보존한다.
            }
        }
        return new CliRequestException(statusCode, objectMapper.createObjectNode()
                .put("message", "요청이 실패했습니다: " + statusCode));
    }

    /**
     * redirect와 비정상 상태를 성공으로 오인하지 않도록 2xx만 허용한다.
     *
     * @param statusCode HTTP 상태 코드
     * @return 200 이상 300 미만이면 true
     */
    private boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 전송·파싱 예외를 내부 세부 정보가 없는 연결 실패로 바꾼다.
     *
     * @return credential이나 요청 header를 포함하지 않는 예외
     */
    private CliRequestException connectionFailure() {
        return new CliRequestException(0, objectMapper.createObjectNode()
                .put("message", "서버에 연결하거나 응답을 해석할 수 없습니다."));
    }

    /**
     * 원격 응답이 Authorization 값을 반사해도 CLI 출력으로 이어지지 않게 한다.
     *
     * @param value 원격 응답 또는 오류 문자열
     * @return 현재 token 원문을 대체한 문자열, 입력이 null이면 null
     */
    private String redactControlApiToken(String value) {
        return value == null ? null : value.replace(controlApiToken, "<redacted>");
    }

    /**
     * JSON escape를 해석한 뒤에도 token이 다시 나타나지 않도록 canonical 문자열에서 재차 가린다.
     *
     * @param value 원격 JSON 문자열
     * @return token 원문이 없는 JSON tree
     * @throws Exception JSON을 해석하거나 다시 만들 수 없는 경우
     */
    private JsonNode parseRedactedJson(String value) throws Exception {
        JsonNode parsed = objectMapper.readTree(value);
        String canonical = objectMapper.writeValueAsString(parsed);
        return objectMapper.readTree(redactControlApiToken(canonical));
    }

    /**
     * API 요청 실패를 나타내는 예외다.
     *
     * <p>책임: HTTP 상태와 problem 본문을 함께 전달해 명령이 입력 오류(2)와
     * 서버 오류(3)를 구분하게 한다.</p>
     */
    public static class CliRequestException extends RuntimeException {

        private final int statusCode;
        private final JsonNode body;

        /**
         * 상태 코드와 응답 본문을 담아 생성한다.
         *
         * @param statusCode HTTP 상태 코드, 연결 실패 시 0
         * @param body 응답 본문
         */
        public CliRequestException(int statusCode, JsonNode body) {
            super(body.path("message").asText(body.path("detail").asText("요청이 실패했습니다.")));
            this.statusCode = statusCode;
            this.body = body;
        }

        /**
         * HTTP 상태 코드를 반환한다.
         *
         * @return 상태 코드
         */
        public int statusCode() {
            return statusCode;
        }

        /**
         * 응답 본문을 반환한다.
         *
         * @return 응답 본문
         */
        public JsonNode body() {
            return body;
        }
    }
}
