package io.outcomeprobe.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Control API token 형식과 비교 경계를 검증한다.
 *
 * <p>책임: 서버·Web·CLI가 공유하는 32~512자 token68 계열 형식과
 * Bearer header의 엄격한 분리를 회귀로 고정한다.</p>
 */
class ControlApiTokenVerifierTest {

    private static final String TOKEN = "control-api-test-token-0123456789abcdef";

    /** 정확한 token과 대소문자가 다른 Bearer scheme을 허용한다. */
    @Test
    void acceptsExactTokenWithCaseInsensitiveBearerScheme() {
        ControlApiTokenVerifier verifier = new ControlApiTokenVerifier(TOKEN);

        assertThat(verifier.matchesBearerHeader("Bearer " + TOKEN)).isTrue();
        assertThat(verifier.matchesBearerHeader("bEaReR " + TOKEN)).isTrue();
    }

    /** token 대소문자와 값은 정확히 일치해야 한다. */
    @Test
    void rejectsDifferentTokenValue() {
        ControlApiTokenVerifier verifier = new ControlApiTokenVerifier(TOKEN);

        assertThat(verifier.matchesBearerHeader(
                "Bearer wrong-control-api-token-0123456789abcdef")).isFalse();
        assertThat(verifier.matchesBearerHeader("Bearer " + TOKEN.toUpperCase())).isFalse();
    }

    /** 누락·다른 scheme·공백 변형·추가 credential은 모두 거부한다. */
    @Test
    void rejectsMalformedAuthorizationHeaders() {
        ControlApiTokenVerifier verifier = new ControlApiTokenVerifier(TOKEN);

        assertThat(verifier.matchesBearerHeader(null)).isFalse();
        assertThat(verifier.matchesBearerHeader("")).isFalse();
        assertThat(verifier.matchesBearerHeader("Basic " + TOKEN)).isFalse();
        assertThat(verifier.matchesBearerHeader("Bearer")).isFalse();
        assertThat(verifier.matchesBearerHeader("Bearer  " + TOKEN)).isFalse();
        assertThat(verifier.matchesBearerHeader("Bearer " + TOKEN + " ")).isFalse();
        assertThat(verifier.matchesBearerHeader("Bearer " + TOKEN + ",extra")).isFalse();
    }

    /** token 길이의 양 끝과 끝 padding 두 글자를 허용한다. */
    @Test
    void acceptsLengthBoundariesAndTrailingPadding() {
        String minimum = "A".repeat(32);
        String maximum = "B".repeat(510) + "==";
        String padded = "C".repeat(32) + "==";

        assertThat(new ControlApiTokenVerifier(minimum)
                .matchesBearerHeader("Bearer " + minimum)).isTrue();
        assertThat(new ControlApiTokenVerifier(maximum)
                .matchesBearerHeader("Bearer " + maximum)).isTrue();
        assertThat(new ControlApiTokenVerifier(padded)
                .matchesBearerHeader("Bearer " + padded)).isTrue();
    }

    /** 너무 짧거나 긴 token은 시작 시 거부한다. */
    @Test
    void rejectsLengthOutsideContract() {
        assertThatThrownBy(() -> new ControlApiTokenVerifier("A".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlApiTokenVerifier("B".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** whitespace·제어문자·Unicode·잘못된 padding은 시작 시 거부한다. */
    @Test
    void rejectsCharactersOutsideTokenContract() {
        String[] invalidTokens = {
            "A".repeat(32) + " ",
            "A".repeat(16) + "\t" + "B".repeat(16),
            "A".repeat(16) + "\n" + "B".repeat(16),
            "A".repeat(31) + "한",
            "A".repeat(16) + "=" + "B".repeat(16),
            "A".repeat(32) + "===",
            "A".repeat(31) + ":",
            "A".repeat(31) + "\\"
        };

        for (String invalidToken : invalidTokens) {
            assertThatThrownBy(() -> new ControlApiTokenVerifier(invalidToken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(invalidToken);
        }
    }

    /** null·빈 값·공백 설정은 원문을 노출하지 않고 fail-closed 처리한다. */
    @Test
    void rejectsMissingOrBlankConfiguration() {
        assertThatThrownBy(() -> new ControlApiTokenVerifier(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlApiTokenVerifier(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlApiTokenVerifier(" ".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(" ".repeat(32));
    }

    /** Spring property 누락도 애플리케이션 컨텍스트 기동을 실패시킨다. */
    @Test
    void missingSpringPropertyFailsApplicationContextStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(TokenOnlyConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Control API token 설정이 올바르지 않습니다.");
                });
    }

    /** token 검증기만 올리는 fail-closed 컨텍스트 구성이다. */
    @Configuration(proxyBeanMethods = false)
    @Import(ControlApiTokenVerifier.class)
    static class TokenOnlyConfiguration {
    }
}
