package org.outcomeprobe.referenceconsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.outcomeprobe.referenceconsumer.booking.BookingProviderClient;
import org.outcomeprobe.referenceconsumer.payment.ProviderPaymentClient;
import org.outcomeprobe.referenceconsumer.support.WebhookSignatureVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Reference Consumer의 환경 변수 바인딩과 Emulator 인증 전달 계약을 검증한다.
 *
 * <p>검출 가능한 결함: 기본 URL로 잘못 요청하거나 ingress token header를 누락하고,
 * callback 검증기가 고정 secret을 암묵적으로 사용하는 문제.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferenceConsumerEnvironmentBindingTest {

    private static final String EMULATOR_TOKEN_HEADER = "X-OutcomeProbe-Emulator-Token";
    private static final String CALLBACK_SECRET = "non-default-callback-secret";
    private static final String EMULATOR_TOKEN = "non-default-emulator-token";
    private static final RecordingServer PAYMENT_SERVER = RecordingServer.start();
    private static final RecordingServer BOOKING_SERVER = RecordingServer.start();

    @Autowired
    private ProviderPaymentClient paymentClient;

    @Autowired
    private BookingProviderClient bookingClient;

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;

    /**
     * 테스트 전용 비기본 환경 값을 실제 Spring property chain에 등록한다.
     *
     * @param registry 동적 property registry
     */
    @DynamicPropertySource
    static void registerEnvironment(DynamicPropertyRegistry registry) {
        registry.add("OUTCOMEPROBE_EMULATOR_BASE_URL",
                () -> PAYMENT_SERVER.baseUrl("/custom-payment"));
        registry.add("OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL",
                () -> BOOKING_SERVER.baseUrl("/custom-booking"));
        registry.add("OUTCOMEPROBE_EMULATOR_TOKEN", () -> EMULATOR_TOKEN);
        registry.add("OUTCOMEPROBE_DEMO_CALLBACK_SECRET", () -> CALLBACK_SECRET);
    }

    /**
     * 비기본 URL·token·callback secret이 실제 client와 검증기에 연결돼야 한다.
     *
     * @throws Exception HMAC 생성에 실패한 경우
     */
    @Test
    void bindsNonDefaultUrlsTokenAndCallbackSecret() throws Exception {
        ProviderPaymentClient.ProviderCallResult paymentResult =
                paymentClient.getPayment("payment-42");
        BookingProviderClient.CallResult bookingResult = bookingClient.getBooking("booking-42");

        RecordedRequest paymentRequest = PAYMENT_SERVER.awaitRequest();
        RecordedRequest bookingRequest = BOOKING_SERVER.awaitRequest();
        assertThat(paymentResult.isSuccess()).isTrue();
        assertThat(bookingResult.isSuccess()).isTrue();
        assertThat(paymentRequest.path()).isEqualTo("/custom-payment/v1/payments/payment-42");
        assertThat(bookingRequest.path()).isEqualTo("/custom-booking/v1/bookings/booking-42");
        assertThat(paymentRequest.emulatorToken()).isEqualTo(EMULATOR_TOKEN);
        assertThat(bookingRequest.emulatorToken()).isEqualTo(EMULATOR_TOKEN);

        byte[] callbackBody = "{\"status\":\"SUCCEEDED\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(signatureVerifier.isValid(callbackBody,
                hmacSha256(CALLBACK_SECRET, callbackBody))).isTrue();
        assertThat(signatureVerifier.isValid(callbackBody,
                hmacSha256("different-secret", callbackBody))).isFalse();
    }

    /**
     * optional token이 비어 있으면 두 client 모두 인증 header를 보내지 않아야 한다.
     */
    @Test
    void omitsEmulatorTokenHeaderWhenTokenIsBlank() {
        try (RecordingServer paymentServer = RecordingServer.start();
                RecordingServer bookingServer = RecordingServer.start()) {
            ProviderPaymentClient noTokenPaymentClient = new ProviderPaymentClient(
                    paymentServer.baseUrl("/payment"), "  ");
            BookingProviderClient noTokenBookingClient = new BookingProviderClient(
                    bookingServer.baseUrl("/booking"), "");

            noTokenPaymentClient.getPayment("payment-no-token");
            noTokenBookingClient.getBooking("booking-no-token");

            assertThat(paymentServer.awaitRequest().emulatorToken()).isNull();
            assertThat(bookingServer.awaitRequest().emulatorToken()).isNull();
        }
    }

    /**
     * callback secret이 없거나 공백이면 HMAC 검증기를 시작하지 않아야 한다.
     */
    @Test
    void rejectsMissingEmptyAndBlankCallbackSecret() {
        for (String invalid : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> new WebhookSignatureVerifier(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Webhook 서명 secret은 비어 있을 수 없습니다.");
        }
    }

    /**
     * 테스트 suite가 끝나면 환경 바인딩 검증용 HTTP server를 정리한다.
     */
    @AfterAll
    static void stopServers() {
        PAYMENT_SERVER.close();
        BOOKING_SERVER.close();
    }

    /**
     * callback body의 HMAC-SHA256 16진수 서명을 만든다.
     *
     * @param secret 서명 secret
     * @param body 서명할 원본 body
     * @return 소문자 16진수 서명
     * @throws Exception HMAC algorithm을 사용할 수 없는 경우
     */
    private static String hmacSha256(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    /**
     * loopback에서 요청을 기록하고 빈 JSON 성공 응답을 반환하는 테스트 server다.
     */
    private static final class RecordingServer implements AutoCloseable {

        private final HttpServer server;
        private final BlockingQueue<RecordedRequest> requests = new LinkedBlockingQueue<>();

        /**
         * 지정된 HTTP server를 감싼다.
         *
         * @param server 이미 loopback에 bind된 server
         */
        private RecordingServer(HttpServer server) {
            this.server = server;
            server.createContext("/", this::record);
            server.start();
        }

        /**
         * 임의 loopback port에서 기록 server를 시작한다.
         *
         * @return 시작된 기록 server
         */
        private static RecordingServer start() {
            try {
                return new RecordingServer(HttpServer.create(
                        new InetSocketAddress("127.0.0.1", 0), 0));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * 테스트용 path prefix가 포함된 base URL을 반환한다.
         *
         * @param pathPrefix 요청 구분용 path prefix
         * @return loopback base URL
         */
        private String baseUrl(String pathPrefix) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + pathPrefix;
        }

        /**
         * 요청 path와 token을 기록하고 HTTP 200을 반환한다.
         *
         * @param exchange 수신한 HTTP exchange
         * @throws IOException 응답 전송에 실패한 경우
         */
        private void record(HttpExchange exchange) throws IOException {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requests.add(new RecordedRequest(
                        exchange.getRequestURI().getRawPath(),
                        exchange.getRequestHeaders().getFirst(EMULATOR_TOKEN_HEADER)));
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        }

        /**
         * 기록된 다음 요청을 제한 시간 안에 반환한다.
         *
         * @return 기록된 요청
         */
        private RecordedRequest awaitRequest() {
            try {
                RecordedRequest request = requests.poll(Duration.ofSeconds(2).toMillis(),
                        TimeUnit.MILLISECONDS);
                if (request == null) {
                    throw new AssertionError("제한 시간 안에 HTTP 요청을 받지 못했습니다.");
                }
                return request;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("HTTP 요청 대기가 중단됐습니다.", e);
            }
        }

        /**
         * loopback HTTP server를 중지한다.
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * client가 보낸 path와 Emulator 인증 token을 보존한다.
     *
     * @param path 요청 path
     * @param emulatorToken Emulator 인증 header 값, 없으면 null
     */
    private record RecordedRequest(String path, String emulatorToken) {
    }
}
