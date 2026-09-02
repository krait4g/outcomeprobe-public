package io.outcomeprobe.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Target base URI가 snapshot에 credential을 우회 저장하지 못하는지 검증한다. */
class TargetProfileUriPolicyTest {

    /** credential·비HTTP scheme·query·fragment·base path·잘못된 port를 모두 거부해야 한다. */
    @Test
    void rejectsUnsafeBaseUris() {
        for (String uri : java.util.List.of(
                "http://alice:plain-password@localhost:8091",
                "ftp://localhost:21/path",
                "https://api.example.test/base/path",
                "https://api.example.test/%2e%2e/admin",
                "http://localhost:8091/path?token=value",
                "http://localhost:8091/path#credential",
                "http://localhost:0",
                "http://localhost:65536",
                "/relative/path")) {
            assertThat(TargetProfileUriPolicy.isSafe(uri)).as(uri).isFalse();
        }
    }

    /** host만 가진 HTTP(S) origin은 root slash 표기와 무관하게 허용해야 한다. */
    @Test
    void acceptsCredentialFreeHttpUris() {
        assertThat(TargetProfileUriPolicy.isSafe("http://localhost:8091")).isTrue();
        assertThat(TargetProfileUriPolicy.isSafe("https://api.example.test/")).isTrue();
        assertThat(TargetProfileUriPolicy.isSafe("https://[::1]:8443")).isTrue();
    }

    /** 상대 request-target은 query를 허용하되 경계 우회 표현은 fail-closed해야 한다. */
    @Test
    void validatesRelativeRequestTargets() {
        assertThat(TargetProfileUriPolicy.isSafeRelativeRequestTarget("/api/orders?limit=10"))
                .isTrue();
        for (String path : java.util.List.of(
                "//evil.test/path",
                "/api/../admin",
                "/api/./orders",
                "/api/%2e%2e/admin",
                "/api/%252e%252e/admin",
                "/api/%2fadmin",
                "/api/%5cadmin",
                "/api\\admin",
                "/api/orders#fragment",
                "/api/%")) {
            assertThat(TargetProfileUriPolicy.isSafeRelativeRequestTarget(path)).as(path).isFalse();
        }
    }
}
