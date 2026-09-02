package io.outcomeprobe.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetProfile}의 path allowlist 신뢰 경계를 검증한다.
 *
 * <p>검출 가능한 결함: traversal 경로 허용, 허용되지 않은 prefix 우회,
 * 상대 경로가 아닌 입력 허용으로 인한 SSRF 노출.</p>
 */
class TargetProfileTest {

    private final TargetProfile profile = new TargetProfile(
            "test",
            "http://localhost:8091",
            List.of("/api/**", "/test-support/**", "/webhooks/payments"),
            List.of("GET", "POST"),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            List.of());

    /**
     * 허용된 glob 경로만 요청할 수 있어야 SSRF 1차 방어가 성립한다.
     */
    @Test
    void allowsListedPaths() {
        assertThat(profile.isPathAllowed("/api/payments/pay-1/confirm")).isTrue();
        assertThat(profile.isPathAllowed("/test-support/payments/pay-1")).isTrue();
        assertThat(profile.isPathAllowed("/webhooks/payments")).isTrue();
        assertThat(profile.isPathAllowed("/api/payments?limit=10")).isTrue();
    }

    /**
     * 목록에 없는 경로와 prefix 우회는 거부돼야 한다.
     */
    @Test
    void rejectsUnlistedPaths() {
        assertThat(profile.isPathAllowed("/admin")).isFalse();
        assertThat(profile.isPathAllowed("/api")).isFalse();
        assertThat(profile.isPathAllowed("/test-supportX")).isFalse();
    }

    /**
     * traversal과 인코딩 우회 시도는 매칭 전에 거부돼야 한다.
     */
    @Test
    void rejectsTraversalAttempts() {
        assertThat(profile.isPathAllowed("/api/../admin")).isFalse();
        assertThat(profile.isPathAllowed("/api/%2e%2e/admin")).isFalse();
        assertThat(profile.isPathAllowed("/api/%2Fadmin")).isFalse();
        assertThat(profile.isPathAllowed("/api/%252e%252e/admin")).isFalse();
        assertThat(profile.isPathAllowed("/api/./admin")).isFalse();
        assertThat(profile.isPathAllowed("//evil.test/api")).isFalse();
        assertThat(profile.isPathAllowed("/api\\admin")).isFalse();
    }

    /**
     * 상대 경로가 아닌 입력은 허용 목록과 무관하게 거부돼야 한다.
     */
    @Test
    void rejectsNonRelativePaths() {
        assertThat(profile.isPathAllowed("api/payments")).isFalse();
        assertThat(profile.isPathAllowed("")).isFalse();
        assertThat(profile.isPathAllowed(null)).isFalse();
    }

    /** host와 port 판정을 분리해 사전 검증 실패 원인을 정확히 표시해야 한다. */
    @Test
    void distinguishesAllowedHostFromDisallowedPort() {
        TargetProfile restricted = new TargetProfile(
                "restricted", "https://Api.Example.Test:8443", List.of("/**"),
                List.of("GET"), List.of("*.example.test"), List.of(9443), Map.of(),
                Map.of(), Map.of(), List.of(), List.of());
        java.net.URI uri = java.net.URI.create("https://api.example.test:8443");

        assertThat(restricted.isOriginHostAllowed(uri)).isTrue();
        assertThat(restricted.isHostAllowed(uri)).isFalse();
    }
}
