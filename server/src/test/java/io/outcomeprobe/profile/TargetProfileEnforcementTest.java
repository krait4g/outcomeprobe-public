package io.outcomeprobe.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TargetProfile의 isMethodAllowed·isHostAllowed·isPathAllowed를 검증한다.
 *
 * <p>검출 가능한 결함: allowedMethods에 없는 HTTP method가 실제 요청으로 나가거나,
 * allowedHosts·allowedPorts가 런타임에서 무시되는 문제.</p>
 */
class TargetProfileEnforcementTest {

    /**
     * 테스트용 TargetProfile fixture를 생성한다.
     */
    private TargetProfile profile(List<String> methods, List<String> hosts, List<Integer> ports) {
        return new TargetProfile("test", "http://localhost:8091",
                List.of("/**"), methods, hosts, ports,
                Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    /**
     * allowedMethods 강제 검증을 확인한다.
     */
    @Nested
    @DisplayName("isMethodAllowed")
    class MethodEnforcement {

        /**
         * 허용된 method는 통과한다.
         */
        @Test
        @DisplayName("GET·POST 허용 시 true")
        void allowedMethodsPass() {
            TargetProfile p = profile(List.of("GET", "POST"), List.of(), List.of());

            assertThat(p.isMethodAllowed("GET")).isTrue();
            assertThat(p.isMethodAllowed("POST")).isTrue();
        }

        /**
         * 허용되지 않은 method는 거부된다.
         */
        @Test
        @DisplayName("DELETE·PATCH 거부")
        void disallowedMethodsRejected() {
            TargetProfile p = profile(List.of("GET", "POST"), List.of(), List.of());

            assertThat(p.isMethodAllowed("DELETE")).isFalse();
            assertThat(p.isMethodAllowed("PATCH")).isFalse();
            assertThat(p.isMethodAllowed("PUT")).isFalse();
        }

        /**
         * 대소문자 무관하게 매칭된다.
         */
        @Test
        @DisplayName("method 대소문자 정규화")
        void caseInsensitive() {
            TargetProfile p = profile(List.of("GET", "POST"), List.of(), List.of());

            assertThat(p.isMethodAllowed("get")).isTrue();
            assertThat(p.isMethodAllowed("Post")).isTrue();
            assertThat(p.isMethodAllowed("delete")).isFalse();
        }

        /**
         * allowedMethods가 비어 있으면 모든 method를 허용한다(하위 호환).
         */
        @Test
        @DisplayName("빈 allowedMethods는 모든 method 허용")
        void emptyAllowsAll() {
            TargetProfile p = profile(List.of(), List.of(), List.of());

            assertThat(p.isMethodAllowed("GET")).isTrue();
            assertThat(p.isMethodAllowed("DELETE")).isTrue();
        }

        /**
         * null·빈 method는 거부된다.
         */
        @Test
        @DisplayName("null·빈 method 거부")
        void nullMethodRejected() {
            TargetProfile p = profile(List.of("GET"), List.of(), List.of());

            assertThat(p.isMethodAllowed(null)).isFalse();
            assertThat(p.isMethodAllowed("")).isFalse();
        }
    }

    /**
     * allowedHosts·allowedPorts 런타임 적용을 확인한다.
     */
    @Nested
    @DisplayName("isHostAllowed")
    class HostPortEnforcement {

        /**
         * 허용된 host·port는 통과한다.
         */
        @Test
        @DisplayName("허용된 host와 port 요청 성공")
        void allowedHostPortPass() {
            TargetProfile p = profile(List.of(), List.of("localhost"), List.of(8091));

            assertThat(p.isHostAllowed(java.net.URI.create("http://localhost:8091/api"))).isTrue();
        }

        /**
         * 허용되지 않은 host는 거부된다.
         */
        @Test
        @DisplayName("허용되지 않은 host 거부")
        void disallowedHostRejected() {
            TargetProfile p = profile(List.of(), List.of("localhost"), List.of());

            assertThat(p.isHostAllowed(java.net.URI.create("http://evil.com:8091/api"))).isFalse();
        }

        /**
         * 허용되지 않은 port는 거부된다.
         */
        @Test
        @DisplayName("허용되지 않은 port 거부")
        void disallowedPortRejected() {
            TargetProfile p = profile(List.of(), List.of("localhost"), List.of(8091));

            assertThat(p.isHostAllowed(java.net.URI.create("http://localhost:9999/api"))).isFalse();
        }

        /**
         * allowedPorts가 비어 있으면 port 제한이 없다.
         */
        @Test
        @DisplayName("빈 allowedPorts는 port 제한 없음")
        void emptyPortsAllowAll() {
            TargetProfile p = profile(List.of(), List.of("localhost"), List.of());

            assertThat(p.isHostAllowed(java.net.URI.create("http://localhost:12345/api"))).isTrue();
        }
    }
}
