package io.outcomeprobe.delivery.adapter.out;

import io.outcomeprobe.delivery.WebhookSenderPort;
import io.outcomeprobe.infrastructure.net.NetworkSafety;
import io.outcomeprobe.profile.TargetProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JDK HttpClient로 Webhook을 전송하는 adapter다.
 *
 * <p>책임: path allowlist·SSRF host 검사·짧은 timeout·redirect 차단으로 전달
 * 시도를 격리한다.</p>
 * <p>핵심 불변식: 허용되지 않은 path·host로는 전송하지 않는다.</p>
 */
@Component
public class HttpClientWebhookSender implements WebhookSenderPort {

    private final HttpClient httpClient;
    private final NetworkSafety networkSafety;

    /**
     * Webhook 전용 client를 구성한다.
     *
     * @param networkSafety SSRF host 검사
     */
    @Autowired
    public HttpClientWebhookSender(NetworkSafety networkSafety) {
        this(networkSafety, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * 테스트 가능한 HTTP client로 Webhook sender를 구성한다.
     *
     * @param networkSafety 요청 직전 SSRF 검사기
     * @param httpClient redirect가 비활성화된 전송 client
     */
    HttpClientWebhookSender(NetworkSafety networkSafety, HttpClient httpClient) {
        this.networkSafety = java.util.Objects.requireNonNull(networkSafety);
        this.httpClient = java.util.Objects.requireNonNull(httpClient);
    }

    /**
     * allowlist와 host 안전성을 검증한 뒤 Webhook을 전송한다.
     *
     * @param profile Target Profile
     * @param path 수신 상대 경로
     * @param headers 요청 header
     * @param body 원본 body 바이트
     * @return 전송 결과
     */
    @Override
    public WebhookSendResult send(TargetProfile profile, String path,
                                  Map<String, String> headers, byte[] body) {
        // SSRF 방어: Profile allowlist에 없는 Webhook 경로는 전송하지 않는다.
        if (!profile.isPathAllowed(path)) {
            return new WebhookSendResult(0, "CALLBACK_TARGET_NOT_ALLOWED");
        }
        // method 강제: Webhook은 POST이므로 POST가 허용되지 않으면 전송하지 않는다.
        if (!profile.isMethodAllowed("POST")) {
            return new WebhookSendResult(0, "TARGET_METHOD_NOT_ALLOWED");
        }
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(profile.baseUri()) + path);
        } catch (IllegalArgumentException exception) {
            return new WebhookSendResult(0, "CALLBACK_TARGET_URI_INVALID");
        }
        // network policy와 SSRF 안전성 검사는 요청 직전에 수행한다(OP-AUDIT-SEC-001).
        if (!profile.isHostAllowed(uri)) {
            return new WebhookSendResult(0, "CALLBACK_TARGET_HOST_NOT_ALLOWED");
        }
        // 요청 직전 host를 다시 검사해 DNS rebinding 위험을 줄인다.
        if (!networkSafety.isSafe(uri)) {
            return new WebhookSendResult(0, "CALLBACK_TARGET_HOST_NOT_ALLOWED");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            HttpResponse<Void> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());
            return new WebhookSendResult(response.statusCode(), null);
        } catch (javax.net.ssl.SSLException exception) {
            return new WebhookSendResult(0, "CALLBACK_TLS_INVALID");
        } catch (java.io.IOException e) {
            return new WebhookSendResult(0, "CONNECTION_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WebhookSendResult(0, "INTERRUPTED");
        } catch (IllegalArgumentException exception) {
            return new WebhookSendResult(0, "CALLBACK_HEADER_INVALID");
        }
    }

    /**
     * base URI 끝 슬래시를 제거해 경로 결합을 안전하게 한다.
     */
    private String stripTrailingSlash(String baseUri) {
        return baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
    }
}
