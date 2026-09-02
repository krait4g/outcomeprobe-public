package io.outcomeprobe.execution.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.execution.TargetHttpPort;
import io.outcomeprobe.infrastructure.net.NetworkSafety;
import io.outcomeprobe.profile.TargetProfile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;

/**
 * Target HTTP adapter의 body deadline과 응답 자원 정리를 검증한다.
 *
 * <p>검출 가능한 결함: header 수신 뒤 멈춘 body로 인한 Run lease 초과,
 * timeout의 비호환 오류 매핑, 성공·실패 경로의 응답 stream 누수.</p>
 */
class HttpClientTargetAdapterTest {

    /** 기본 HTTP budget은 10초 Run lease보다 충분히 짧아야 한다. */
    @Test
    @DisplayName("기본 Target HTTP budget은 Run lease보다 짧게 고정한다")
    void defaultBudgetLeavesLeaseCleanupMargin() {
        assertThat(HttpClientTargetAdapter.REQUEST_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(5))
                .isLessThan(Duration.ofSeconds(10));
    }

    /**
     * 멈춘 body는 전체 요청 제한 안에서 연결 실패로 반환하고 reader까지 정리한다.
     *
     * @throws Exception mock HTTP 호출 또는 대기 검증이 실패하면 발생한다
     */
    @Test
    @Timeout(2)
    @DisplayName("멈춘 Target body는 제한 시간 안에 typed 실패하고 자원을 정리한다")
    void stalledBodyReturnsTypedFailureAndReleasesResources() throws Exception {
        NetworkSafety networkSafety = mock(NetworkSafety.class);
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> httpResponse = mock(HttpResponse.class);
        BlockingInputStream body = new BlockingInputStream();
        Duration requestTimeout = Duration.ofMillis(200);
        when(networkSafety.isSafe(any(URI.class))).thenReturn(true);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(httpResponse);
        HttpClientTargetAdapter adapter = new HttpClientTargetAdapter(
                networkSafety, new ObjectMapper(), httpClient, requestTimeout);

        long startedNanos = System.nanoTime();
        TargetHttpPort.TargetHttpResponse response = adapter.call(
                profile(), "POST", "/trigger", Map.of(), "{}");
        long elapsedNanos = System.nanoTime() - startedNanos;

        assertThat(response.reached()).isFalse();
        assertThat(response.failureCode()).isEqualTo("TARGET_CONNECTION_FAILED");
        assertThat(response.statusCode()).isZero();
        assertThat(elapsedNanos).isLessThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(body.readStarted()).isTrue();
        assertThat(body.closed()).isTrue();
        assertThat(body.awaitReadFinished()).isTrue();
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(),
                ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(request.getValue().timeout()).contains(requestTimeout);
        verify(networkSafety).isSafe(URI.create("http://target.example/trigger"));
    }

    /**
     * 제한 안에서 끝난 body는 기존 응답 계약을 유지하고 stream을 닫는다.
     *
     * @throws Exception mock HTTP 호출이 실패하면 발생한다
     */
    @Test
    @DisplayName("완료된 Target body는 기존 응답 계약을 유지하고 stream을 닫는다")
    void completedBodyPreservesResponseContractAndClosesStream() throws Exception {
        NetworkSafety networkSafety = mock(NetworkSafety.class);
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> httpResponse = mock(HttpResponse.class);
        String responseText = "{\"accepted\":true}";
        ClosingInputStream body = new ClosingInputStream(
                responseText.getBytes(StandardCharsets.UTF_8));
        when(networkSafety.isSafe(any(URI.class))).thenReturn(true);
        when(httpResponse.statusCode()).thenReturn(202);
        when(httpResponse.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(httpResponse);
        HttpClientTargetAdapter adapter = new HttpClientTargetAdapter(
                networkSafety, new ObjectMapper(), httpClient, Duration.ofSeconds(1));

        TargetHttpPort.TargetHttpResponse response = adapter.call(
                profile(), "POST", "/trigger", Map.of(), "{}");

        assertThat(response.reached()).isTrue();
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.failureCode()).isNull();
        assertThat(response.bodyText()).isEqualTo(responseText);
        assertThat(response.body().path("accepted").asBoolean()).isTrue();
        assertThat(body.closed()).isTrue();
    }

    /** 테스트 호출을 허용하는 안전한 Target Profile을 만든다. */
    private TargetProfile profile() {
        return new TargetProfile("target", "http://target.example",
                List.of("/**"), List.of("POST"), List.of(), List.of(),
                Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    /** close 전에는 읽기를 끝내지 않는 테스트용 응답 stream이다. */
    private static final class BlockingInputStream extends InputStream {

        private final CountDownLatch closeSignal = new CountDownLatch(1);
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch readFinished = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        /** close 신호까지 body read를 멈춘 상태로 유지한다. */
        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                closeSignal.await();
                return -1;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("테스트 Target body reader가 중단됐습니다.", exception);
            } finally {
                readFinished.countDown();
            }
        }

        /** 대기 중인 read를 해제하고 stream 닫힘을 기록한다. */
        @Override
        public void close() {
            closed.set(true);
            closeSignal.countDown();
        }

        /** @return body read가 실제로 시작됐으면 true */
        private boolean readStarted() {
            return readStarted.getCount() == 0;
        }

        /** @return stream close가 호출됐으면 true */
        private boolean closed() {
            return closed.get();
        }

        /**
         * reader가 close 또는 interrupt 뒤 종료됐는지 기다린다.
         *
         * @return 1초 안에 reader가 종료됐으면 true
         * @throws InterruptedException 테스트 thread가 중단되면 발생한다
         */
        private boolean awaitReadFinished() throws InterruptedException {
            return readFinished.await(1, TimeUnit.SECONDS);
        }
    }

    /** 완료 body의 close 호출을 관측하는 테스트용 stream이다. */
    private static final class ClosingInputStream extends ByteArrayInputStream {

        private final AtomicBoolean closed = new AtomicBoolean();

        /** @param bytes 읽은 뒤 종료할 응답 바이트 */
        private ClosingInputStream(byte[] bytes) {
            super(bytes);
        }

        /** stream 닫힘을 기록한 뒤 기본 정리를 수행한다. */
        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }

        /** @return stream close가 호출됐으면 true */
        private boolean closed() {
            return closed.get();
        }
    }
}
