package io.outcomeprobe.infrastructure.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * {@link NetworkSafety}의 SSRF host 차단 규칙을 검증한다.
 *
 * <p>검출 가능한 결함: 클라우드 metadata(169.254.169.254)·link-local·multicast·
 * unspecified 주소로 요청이 나가는 문제. 로컬·Docker 사설망은 허용돼야 한다.</p>
 */
class NetworkSafetyTest {

    private final NetworkSafety networkSafety = new NetworkSafety();

    /**
     * 로컬·사설 주소는 local 도구 특성상 허용돼야 Quick Start가 동작한다.
     */
    @Test
    void allowsLocalAndPrivateHosts() {
        assertThat(networkSafety.isSafe(URI.create("http://localhost:8091/api/x"))).isTrue();
        assertThat(networkSafety.isSafe(URI.create("http://127.0.0.1:8091/api/x"))).isTrue();
    }

    /**
     * 클라우드 metadata를 포함한 link-local 주소는 차단돼야 한다.
     */
    @Test
    void blocksLinkLocalMetadata() {
        assertThat(networkSafety.isSafe(URI.create("http://169.254.169.254/latest/meta-data")))
                .isFalse();
    }

    /**
     * multicast와 unspecified 주소는 차단돼야 한다.
     */
    @Test
    void blocksMulticastAndUnspecified() {
        assertThat(networkSafety.isSafe(URI.create("http://224.0.0.1/x"))).isFalse();
        assertThat(networkSafety.isSafe(URI.create("http://0.0.0.0/x"))).isFalse();
    }

    /**
     * 해석할 수 없는 host는 안전한 대상으로 보지 않는다.
     */
    @Test
    void rejectsUnresolvableHost() {
        assertThat(networkSafety.isSafe(URI.create("http://no-such-host.invalid/x"))).isFalse();
    }
}
