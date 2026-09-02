package org.outcomeprobe.referenceconsumer.booking;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 예약 Provider(OutcomeProbe Emulator mockbooking alias) 호출 client다.
 *
 * <p>책임: 짧은 timeout으로 명령 결과 모호성을 재현한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: Provider 내부 상태를 직접 알 수 없어
 * 모호한 결과는 정책과 재조정 loop가 해결한다.</p>
 */
@Component
public class BookingProviderClient {

    private static final String EMULATOR_TOKEN_HEADER = "X-OutcomeProbe-Emulator-Token";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String emulatorToken;

    /**
     * booking Provider base URL을 주입한다.
     *
     * @param baseUrl mockbooking alias를 가리키는 base URL
     * @param emulatorToken Emulator ingress token, 비어 있으면 header를 보내지 않는다
     */
    public BookingProviderClient(
            @Value("${reference-consumer.booking-provider.base-url}") String baseUrl,
            @Value("${reference-consumer.emulator-token}") String emulatorToken) {
        this.baseUrl = baseUrl;
        this.emulatorToken = emulatorToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
    }

    /**
     * Provider 취소를 요청한다.
     *
     * @param bookingId 예약 식별자
     * @return 응답 결과
     */
    public CallResult cancel(String bookingId) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/bookings/" + bookingId + "/cancel"))
                .timeout(Duration.ofMillis(500))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    /**
     * Provider 일정 변경을 요청한다.
     *
     * @param bookingId 예약 식별자
     * @param startsAt 변경 시작 시각
     * @return 응답 결과
     */
    public CallResult reschedule(String bookingId, String startsAt) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/bookings/" + bookingId + "/reschedule"))
                .timeout(Duration.ofMillis(500))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"startsAt\":\"" + startsAt + "\"}")));
    }

    /**
     * Provider 예약 상태를 조회한다.
     *
     * @param bookingId 예약 식별자
     * @return 응답 결과
     */
    public CallResult getBooking(String bookingId) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/bookings/" + bookingId))
                .timeout(Duration.ofMillis(500))
                .GET());
    }

    /**
     * 요청을 전송하고 결과를 관측한다.

     * @param builder 전송할 요청 builder
     * @return 관측 결과
     */
    private CallResult send(HttpRequest.Builder builder) {
        try {
            if (!emulatorToken.isBlank()) {
                builder.header(EMULATOR_TOKEN_HEADER, emulatorToken);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new CallResult(response.statusCode(), response.body(), null);
        } catch (java.net.http.HttpTimeoutException e) {
            return new CallResult(0, null, "TIMEOUT");
        } catch (java.io.IOException e) {
            return new CallResult(0, null, "CONNECTION_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CallResult(0, null, "INTERRUPTED");
        }
    }

    /**
     * Provider 호출 결과를 표현한다.
     *
     * @param statusCode HTTP 상태 코드, 실패 시 0
     * @param body 응답 body
     * @param failureCode 실패 사유, 성공 시 null
     */
    public record CallResult(int statusCode, String body, String failureCode) {

        /**
         * 2xx 응답 여부다.
         *
         * @return 성공이면 true
         */
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
