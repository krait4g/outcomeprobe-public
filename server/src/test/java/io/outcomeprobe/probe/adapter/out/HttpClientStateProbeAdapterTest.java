package io.outcomeprobe.probe.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.infrastructure.net.NetworkSafety;
import io.outcomeprobe.probe.ProbeObservation;
import io.outcomeprobe.profile.TargetProfile;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Probe HTTP adapter의 body read deadline과 stream 정리를 검증한다. */
class HttpClientStateProbeAdapterTest {

    /** Profile이 POST를 허용해도 반복 State Probe는 GET 외 method를 보내지 않아야 한다. */
    @Test
    void rejectsMutatingMethodBeforeNetworkCheck() {
        ClockProvider clock = mock(ClockProvider.class);
        when(clock.now()).thenReturn(Instant.EPOCH);
        NetworkSafety networkSafety = mock(NetworkSafety.class);
        HttpClientStateProbeAdapter adapter = new HttpClientStateProbeAdapter(
                clock, new ObjectMapper(), networkSafety);
        TargetProfile profile = new TargetProfile(
                "target", "http://localhost:8091", List.of("/**"),
                List.of("GET", "POST"), List.of(), List.of(), Map.of(), Map.of(),
                Map.of(), List.of(), List.of());

        ProbeObservation observation = adapter.probe(
                profile, "POST", "/state", Map.of(), Duration.ofSeconds(1));

        assertThat(observation.failureCode()).isEqualTo("TARGET_METHOD_NOT_ALLOWED");
        assertThat(observation.statusCode()).isZero();
        verifyNoInteractions(networkSafety);
    }

    /** body가 멈추면 제한 시간에 중단하고 stream을 닫아야 한다. */
    @Test
    void closesStalledBodyAtDeadline() {
        HttpClientStateProbeAdapter adapter = new HttpClientStateProbeAdapter(
                mock(ClockProvider.class), new ObjectMapper(), mock(NetworkSafety.class));
        BlockingInputStream body = new BlockingInputStream();

        assertThatThrownBy(() -> adapter.readBodyWithin(
                body, TimeUnit.MILLISECONDS.toNanos(20)))
                .isInstanceOf(TimeoutException.class);

        assertThat(body.closed()).isTrue();
    }

    /** close 전에는 읽기를 끝내지 않는 테스트용 stream이다. */
    private static final class BlockingInputStream extends InputStream {

        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        /** close 신호 전까지 읽기를 대기한다. */
        @Override
        public int read() throws IOException {
            try {
                closedLatch.await();
                return -1;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("테스트 stream이 중단됐습니다.", exception);
            }
        }

        /** 대기 중인 read를 해제하고 닫힘 상태를 기록한다. */
        @Override
        public void close() {
            closed.set(true);
            closedLatch.countDown();
        }

        /** stream이 닫혔는지 반환한다. */
        private boolean closed() {
            return closed.get();
        }
    }
}
