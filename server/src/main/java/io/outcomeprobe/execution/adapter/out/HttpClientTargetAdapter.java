package io.outcomeprobe.execution.adapter.out;

import io.outcomeprobe.execution.TargetHttpPort;
import io.outcomeprobe.infrastructure.net.BoundedResponseBody;
import io.outcomeprobe.infrastructure.net.NetworkSafety;
import io.outcomeprobe.profile.TargetProfile;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * JDK HttpClient로 Target trigger 요청을 전송하는 adapter다.
 *
 * <p>책임: path allowlist, SSRF host 검사, timeout, redirect 차단으로 요청을
 * 격리한다.</p>
 * <p>핵심 불변식: 허용되지 않은 path·host로는 전송하지 않는다. 이 adapter의
 * 실패는 재시도 가능 여부와 함께 반환된다. 응답 header를 받은 뒤에도 최초 요청의
 * deadline을 연장하지 않으며 body stream은 성공·실패·timeout 모두 닫는다.</p>
 */
@Component
public class HttpClientTargetAdapter implements TargetHttpPort {

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final NetworkSafety networkSafety;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    /**
     * Target 요청 전용 client를 구성한다.
     *
     * <p>테스트 제어용 보조 생성자와 구분하기 위해 이 생성자를 Spring 주입 경계로
     * 명시한다. 운영 Bean은 고정된 timeout·redirect 정책을 항상 사용한다.</p>
     *
     * @param networkSafety SSRF host 검사
     * @param objectMapper 응답 body 해석 mapper
     */
    @Autowired
    public HttpClientTargetAdapter(NetworkSafety networkSafety, ObjectMapper objectMapper) {
        this(networkSafety, objectMapper, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                REQUEST_TIMEOUT);
    }

    /**
     * 제어 가능한 client와 timeout으로 Target adapter를 구성한다.
     *
     * <p>프로덕션 경로는 공개 생성자의 고정 5초 제한을 사용한다. 이 생성자는 네트워크
     * 서버 없이 body 정지와 자원 정리를 재현할 수 있도록 package 안에만 노출한다.</p>
     *
     * @param networkSafety SSRF host 검사
     * @param objectMapper 응답 body 해석 mapper
     * @param httpClient 요청을 전송할 JDK HTTP client
     * @param requestTimeout 연결부터 body 완료까지 허용할 최대 시간
     * @throws IllegalArgumentException timeout이 0 이하이면 발생한다
     */
    HttpClientTargetAdapter(NetworkSafety networkSafety, ObjectMapper objectMapper,
                            HttpClient httpClient, Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Target HTTP timeout은 0보다 커야 합니다.");
        }
        this.networkSafety = networkSafety;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    /**
     * allowlist와 host 안전성을 검증한 뒤 요청을 전송한다.
     *
     * @param profile Target Profile
     * @param method HTTP method
     * @param path 수신 상대 경로
     * @param headers 요청 header
     * @param bodyText 요청 body 문자열
     * @return 전송 결과
     */
    @Override
    public TargetHttpResponse call(TargetProfile profile, String method, String path,
                                   Map<String, String> headers, String bodyText) {
        // SSRF 방어: Profile allowlist에 없는 경로는 전송하지 않는다.
        if (!profile.isPathAllowed(path)) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "TARGET_PATH_NOT_ALLOWED");
        }
        // method 강제: Profile allowedMethods에 없는 method는 요청 전에 차단한다.
        if (!profile.isMethodAllowed(method)) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "TARGET_METHOD_NOT_ALLOWED");
        }
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(profile.baseUri()) + path);
        } catch (IllegalArgumentException exception) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "TARGET_URI_INVALID");
        }
        // network policy와 SSRF 안전성 검사는 요청 직전에 수행한다(OP-AUDIT-SEC-001).
        if (!profile.isHostAllowed(uri)) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "TARGET_HOST_NOT_ALLOWED");
        }
        if (!networkSafety.isSafe(uri)) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "TARGET_HOST_NOT_ALLOWED");
        }
        long timeoutDeadline = System.nanoTime() + requestTimeout.toNanos();
        try {
            HttpRequest.BodyPublisher publisher = bodyText == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(bodyText);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .method(method, publisher);
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                // redirect body를 기다리지 않고 닫아 새 목적지와 느린 body를 함께 거부한다.
                try (InputStream ignored = response.body()) {
                    return new TargetHttpResponse(response.statusCode(), null, null,
                            java.time.Instant.now(), "TARGET_REDIRECT_REJECTED");
                }
            }
            long remainingNanos = timeoutDeadline - System.nanoTime();
            if (remainingNanos <= 0) {
                response.body().close();
                return new TargetHttpResponse(0, null, null,
                        java.time.Instant.now(), "TARGET_CONNECTION_FAILED");
            }
            BoundedResponseBody captured = readBodyWithin(response.body(), remainingNanos);
            if (captured.tooLarge()) {
                return new TargetHttpResponse(response.statusCode(), null, null,
                        java.time.Instant.now(), "TARGET_RESPONSE_TOO_LARGE");
            }
            String responseBody;
            try {
                responseBody = captured.utf8Text();
            } catch (java.nio.charset.CharacterCodingException exception) {
                return new TargetHttpResponse(response.statusCode(), null, null,
                        java.time.Instant.now(), "TARGET_RESPONSE_UNEXPECTED");
            }
            return new TargetHttpResponse(response.statusCode(), responseBody,
                    parseBody(responseBody), java.time.Instant.now(), null);
        } catch (javax.net.ssl.SSLException exception) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(),
                    "TARGET_TLS_INVALID");
        } catch (java.io.IOException e) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "TARGET_CONNECTION_FAILED");
        } catch (TimeoutException exception) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(),
                    "TARGET_CONNECTION_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "INTERRUPTED");
        } catch (IllegalArgumentException exception) {
            return new TargetHttpResponse(0, null, null, java.time.Instant.now(), "TARGET_HEADER_INVALID");
        }
    }

    /**
     * 응답 body를 최초 요청 deadline 안에서 읽고 모든 종료 경로에서 stream을 닫는다.
     *
     * <p>JDK HttpClient의 request timeout만으로는 {@code ofInputStream()} 반환 뒤 멈춘
     * body read를 강제로 끝낼 수 없다. 별도 virtual thread에서 bounded read를 수행하고
     * timeout이면 stream close와 task cancel을 함께 적용한다.</p>
     *
     * @param input HTTP 응답 stream
     * @param remainingNanos header 수신 뒤 남은 실제 시간
     * @return 바이트 상한 안에서 완전히 읽은 응답
     * @throws java.io.IOException stream 읽기 또는 정리에 실패하면 발생한다
     * @throws InterruptedException 호출 thread가 중단되면 발생한다
     * @throws TimeoutException 남은 시간 안에 body를 다 읽지 못하면 발생한다
     */
    BoundedResponseBody readBodyWithin(InputStream input, long remainingNanos)
            throws java.io.IOException, InterruptedException, TimeoutException {
        ExecutorService reader = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("outcomeprobe-target-body-", 0).factory());
        Future<BoundedResponseBody> future = reader.submit(
                () -> BoundedResponseBody.read(input, MAX_BODY_BYTES));
        try {
            return future.get(Math.max(1L, remainingNanos), TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof java.io.IOException ioException) {
                throw ioException;
            }
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new java.io.IOException("Target 응답 body를 읽을 수 없습니다.",
                    exception.getCause());
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
     * 응답 body를 JSON으로 파싱한다. 파싱 실패는 null로 관측한다.
     */
    private tools.jackson.databind.JsonNode parseBody(String body) {
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
     * base URI 끝의 슬래시를 제거해 경로 결합을 안전하게 한다.
     */
    private String stripTrailingSlash(String baseUri) {
        return baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
    }
}
