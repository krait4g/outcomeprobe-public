package org.outcomeprobe.referenceconsumer.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 외부 결제 Provider(OutcomeProbe Emulator)를 호출하는 client다.
 *
 * <p>책임: 짧은 connect·read timeout으로 Provider 응답 유실 상황을 현실적으로
 * 경험한다. 이 timeout이 TIMEOUT_AFTER_COMMIT 재현의 핵심 조건이다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 client는 Provider 내부 상태를 알 수 없다.
 * 모호한 결과는 호출 정책({@link AmbiguousPaymentPolicy})이 처리한다.</p>
 */
@Component
public class ProviderPaymentClient {

    private static final String EMULATOR_TOKEN_HEADER = "X-OutcomeProbe-Emulator-Token";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String emulatorToken;

    /**
     * Provider base URL을 주입하고 짧은 timeout client를 구성한다.
     *
     * @param baseUrl Emulator alias를 가리키는 base URL
     * @param emulatorToken Emulator ingress token, 비어 있으면 header를 보내지 않는다
     */
    public ProviderPaymentClient(
            @Value("${reference-consumer.provider.base-url}") String baseUrl,
            @Value("${reference-consumer.emulator-token}") String emulatorToken) {
        this.baseUrl = baseUrl;
        this.emulatorToken = emulatorToken;
        // 명세 기준: connect 200ms, read 500ms. 2초 지연 fault에서 반드시 timeout을 경험한다.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
    }

    /**
     * Provider 승인 API를 호출한다.
     *
     * @param paymentId 결제 식별자
     * @param amount 승인 금액
     * @param idempotencyKey 멱등 키
     * @return 응답 결과, timeout·연결 실패 시 failureCode를 가진다
     */
    public ProviderCallResult confirm(String paymentId, long amount, String idempotencyKey) {
        HttpRequest request = requestBuilder(
                        URI.create(baseUrl + "/v1/payments/" + paymentId + "/confirm"))
                .timeout(Duration.ofMillis(500))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":" + amount + "}"))
                .build();
        return send(request);
    }

    /**
     * Provider 상태 조회 API를 호출한다.
     *
     * @param paymentId 결제 식별자
     * @return 응답 결과
     */
    public ProviderCallResult getPayment(String paymentId) {
        HttpRequest request = requestBuilder(
                        URI.create(baseUrl + "/v1/payments/" + paymentId))
                .timeout(Duration.ofMillis(500))
                .GET()
                .build();
        return send(request);
    }

    /**
     * Provider 환불 API를 호출한다.
     *
     * @param paymentId 결제 식별자
     * @param idempotencyKey 멱등 키
     * @return 응답 결과
     */
    public ProviderCallResult refund(String paymentId, String idempotencyKey) {
        HttpRequest request = requestBuilder(
                        URI.create(baseUrl + "/v1/payments/" + paymentId + "/refund"))
                .timeout(Duration.ofMillis(500))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return send(request);
    }

    /**
     * 요청을 전송하고 결과를 관측한다. timeout·연결 실패는 모호한 결과로 반환한다.
     */
    private ProviderCallResult send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return new ProviderCallResult(response.statusCode(), response.body(), null);
        } catch (java.net.http.HttpTimeoutException e) {
            // Provider는 commit했더라도 응답을 받지 못하면 호출자는 결과를 모른다.
            return new ProviderCallResult(0, null, "TIMEOUT");
        } catch (java.io.IOException e) {
            return new ProviderCallResult(0, null, "CONNECTION_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProviderCallResult(0, null, "INTERRUPTED");
        }
    }

    /**
     * 공통 Emulator 인증 header가 적용된 요청 builder를 만든다.
     *
     * @param uri 요청 URI
     * @return token이 비어 있으면 인증 header가 없는 요청 builder
     */
    private HttpRequest.Builder requestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if (!emulatorToken.isBlank()) {
            builder.header(EMULATOR_TOKEN_HEADER, emulatorToken);
        }
        return builder;
    }

    /**
     * Provider 호출 결과를 표현한다.
     *
     * @param statusCode HTTP 상태 코드, 실패 시 0
     * @param body 응답 body
     * @param failureCode 실패 사유, 성공 시 null
     */
    public record ProviderCallResult(int statusCode, String body, String failureCode) {

        /**
         * 2xx 응답을 받았는지 여부다.
         *
         * @return 성공 응답이면 true
         */
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
