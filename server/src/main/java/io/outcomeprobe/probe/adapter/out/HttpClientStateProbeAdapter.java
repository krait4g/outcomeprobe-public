package io.outcomeprobe.probe.adapter.out;

import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.infrastructure.net.BoundedResponseBody;
import io.outcomeprobe.infrastructure.net.NetworkSafety;
import io.outcomeprobe.probe.ProbeObservation;
import io.outcomeprobe.probe.StateProbePort;
import io.outcomeprobe.profile.TargetProfile;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JDK HttpClient로 Target 상태를 조회하는 StateProbePort 구현이다.
 *
 * <p>책임: path allowlist·SSRF host 검사·timeout·redirect 차단·body 크기 제한을
 * 적용해 안전한 관측 결과를 만든다.</p>
 * <p>핵심 불변식: 허용되지 않은 path·host로는 조회하지 않는다.</p>
 */
@Component
public class HttpClientStateProbeAdapter implements StateProbePort {

    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final HttpClient httpClient;
    private final ClockProvider clockProvider;
    private final ObjectMapper objectMapper;
    private final NetworkSafety networkSafety;

    /**
     * Probe 전용 client를 구성한다.
     *
     * @param clockProvider 관측 시각 제공자
     * @param objectMapper JSON 파싱 mapper
     * @param networkSafety SSRF host 검사
     */
    public HttpClientStateProbeAdapter(ClockProvider clockProvider, ObjectMapper objectMapper,
                                       NetworkSafety networkSafety) {
        this.clockProvider = clockProvider;
        this.objectMapper = objectMapper;
        this.networkSafety = networkSafety;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * allowlist와 host 안전성을 검증한 뒤 상태를 조회한다.
     *
     * @param profile 허용된 Target Profile
     * @param method HTTP method
     * @param path Profile 기준 상대 경로
     * @param headers 추가 요청 header
     * @param maximumDuration 연결과 body 전체 처리 제한 시간
     * @return 관측 결과
     */
    @Override
    public ProbeObservation probe(TargetProfile profile, String method, String path,
                                  Map<String, String> headers, Duration maximumDuration) {
        Instant requestedAt = clockProvider.now();
        Duration requestTimeout = maximumDuration.compareTo(Duration.ofSeconds(5)) > 0
                ? Duration.ofSeconds(5) : maximumDuration;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            return failed(requestedAt, 0, null, "TARGET_CONNECTION_FAILED");
        }
        long timeoutDeadline = System.nanoTime() + requestTimeout.toNanos();
        // SSRF 방어: Profile allowlist에 없는 경로는 조회하지 않는다.
        if (!profile.isPathAllowed(path)) {
            return failed(requestedAt, 0, null, "TARGET_PATH_NOT_ALLOWED");
        }
        // State Probe는 반복 조회이므로 부작용 가능 method를 Profile 허용 여부와 무관하게 차단한다.
        if (!"GET".equalsIgnoreCase(method)) {
            return failed(requestedAt, 0, null, "TARGET_METHOD_NOT_ALLOWED");
        }
        String probeMethod = "GET";
        // method 강제: Profile allowedMethods에 없는 method는 요청 전에 차단한다.
        if (!profile.isMethodAllowed(probeMethod)) {
            return failed(requestedAt, 0, null, "TARGET_METHOD_NOT_ALLOWED");
        }
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(profile.baseUri()) + path);
        } catch (IllegalArgumentException exception) {
            return failed(requestedAt, 0, null, "TARGET_URI_INVALID");
        }
        // network policy와 SSRF 안전성 검사는 요청 직전에 수행한다(OP-AUDIT-SEC-001).
        if (!profile.isHostAllowed(uri)) {
            return failed(requestedAt, 0, null, "TARGET_HOST_NOT_ALLOWED");
        }
        // 요청 직전 host를 다시 검사해 DNS rebinding 위험을 줄인다.
        if (!networkSafety.isSafe(uri)) {
            return failed(requestedAt, 0, null, "TARGET_HOST_NOT_ALLOWED");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .method(probeMethod, HttpRequest.BodyPublishers.noBody());
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                // redirect는 body를 기다리지 않고 즉시 닫아 새 목적지와 slow body를 모두 거부한다.
                try (InputStream ignored = response.body()) {
                    return new ProbeObservation(requestedAt, clockProvider.now(),
                            response.statusCode(), null, null,
                            "TARGET_REDIRECT_REJECTED");
                }
            }
            long remainingNanos = timeoutDeadline - System.nanoTime();
            if (remainingNanos <= 0) {
                response.body().close();
                return failed(requestedAt, response.statusCode(), null,
                        "TARGET_CONNECTION_FAILED");
            }
            BoundedResponseBody captured = readBodyWithin(
                    response.body(), remainingNanos);
            Instant respondedAt = clockProvider.now();
            if (captured.tooLarge()) {
                return new ProbeObservation(requestedAt, respondedAt, response.statusCode(),
                        null, null, "TARGET_RESPONSE_TOO_LARGE");
            }
            byte[] responseBytes = captured.bytes();
            String responseHash = HashValue.sha256(responseBytes).hexValue();
            String bodyText;
            try {
                bodyText = captured.utf8Text();
            } catch (java.nio.charset.CharacterCodingException exception) {
                return new ProbeObservation(requestedAt, respondedAt, response.statusCode(),
                        responseHash, null, "PROBE_RESPONSE_INVALID_JSON");
            }
            JsonNode body = parseBody(bodyText);
            if (body == null) {
                return new ProbeObservation(requestedAt, respondedAt, response.statusCode(),
                        responseHash, null, "PROBE_RESPONSE_INVALID_JSON");
            }
            return new ProbeObservation(requestedAt, respondedAt, response.statusCode(),
                    responseHash, body, null);
        } catch (javax.net.ssl.SSLException exception) {
            return failed(requestedAt, 0, null, "TARGET_TLS_INVALID");
        } catch (java.io.IOException e) {
            return failed(requestedAt, 0, null, "TARGET_CONNECTION_FAILED");
        } catch (TimeoutException exception) {
            return failed(requestedAt, 0, null, "TARGET_CONNECTION_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(requestedAt, 0, null, "INTERRUPTED");
        } catch (IllegalArgumentException exception) {
            return failed(requestedAt, 0, null, "TARGET_HEADER_INVALID");
        }
    }

    /**
     * 응답 body를 남은 실제 시간 안에서 읽고 timeout이면 stream을 강제로 닫는다.
     *
     * @param input HTTP 응답 stream
     * @param remainingNanos 헤더 수신 뒤 남은 실제 시간
     * @return 바이트 상한 안의 완전한 응답
     * @throws IOException stream 읽기 실패
     * @throws InterruptedException 호출 thread 중단
     * @throws TimeoutException 남은 시간 안에 body를 다 읽지 못함
     */
    BoundedResponseBody readBodyWithin(InputStream input, long remainingNanos)
            throws IOException, InterruptedException, TimeoutException {
        ExecutorService reader = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("outcomeprobe-probe-body-", 0).factory());
        Future<BoundedResponseBody> future = reader.submit(
                () -> BoundedResponseBody.read(input, MAX_BODY_BYTES));
        try {
            return future.get(Math.max(1L, remainingNanos), TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Probe 응답 body를 읽을 수 없습니다.", exception.getCause());
        } finally {
            try {
                input.close();
            } finally {
                future.cancel(true);
                reader.shutdownNow();
            }
        }
    }

    /**
     * 요청 전 차단 또는 연결 실패를 실제 관측 시각과 함께 만든다.
     *
     * @param requestedAt 시도 시작 시각
     * @param statusCode 관측된 HTTP 상태, 없으면 0
     * @param responseHash 완전히 받은 응답 해시, 없으면 null
     * @param failureCode 고정 실패 코드
     * @return 원문 body가 없는 실패 관측
     */
    private ProbeObservation failed(Instant requestedAt, int statusCode,
                                    String responseHash, String failureCode) {
        return new ProbeObservation(requestedAt, clockProvider.now(), statusCode,
                responseHash, null, failureCode);
    }

    /**
     * 응답 body를 JSON으로 파싱한다. 실패 시 null이다.
     */
    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * base URI 끝 슬래시를 제거한다.
     */
    private String stripTrailingSlash(String baseUri) {
        return baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
    }
}
