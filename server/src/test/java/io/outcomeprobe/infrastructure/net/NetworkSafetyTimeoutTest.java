package io.outcomeprobe.infrastructure.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * DNS 해석 지연이 outbound lease 안전 시간을 침범하지 않도록 bounded 경계를 검증한다.
 *
 * <p>검출 가능한 결함: resolver가 멈췄을 때 Callback Worker가 request timeout 이전 단계에서
 * 무기한 대기해 같은 occurrence가 동시 재취득되는 회귀다.</p>
 */
class NetworkSafetyTimeoutTest {

    /** 느린 DNS는 제한 시간 안에 fail-closed하고 호출자 interrupt 상태를 오염시키지 않는다. */
    @Test
    void slowResolutionFailsClosedWithinBound() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        NetworkSafety safety = new NetworkSafety(host -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.net.UnknownHostException("중단된 테스트 resolver");
            }
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        }, executor, Duration.ofMillis(20));
        long started = System.nanoTime();

        try {
            NetworkSafety.Assessment assessment = safety.assess(
                    URI.create("http://slow.test/callback"));
            assertThat(assessment.resolved()).isFalse();
            assertThat(assessment.safe()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            safety.shutdown();
        }
    }

    /** 정상적으로 반환된 안전 주소는 timeout wrapper 뒤에도 기존 판정을 유지해야 한다. */
    @Test
    void resolvedSafeAddressKeepsExistingDecision() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        NetworkSafety safety = new NetworkSafety(
                host -> new InetAddress[]{InetAddress.getLoopbackAddress()},
                executor, Duration.ofSeconds(1));

        try {
            NetworkSafety.Assessment assessment = safety.assess(
                    URI.create("http://localhost/callback"));
            assertThat(assessment.resolved()).isTrue();
            assertThat(assessment.safe()).isTrue();
        } finally {
            safety.shutdown();
        }
    }
}
