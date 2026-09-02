package io.outcomeprobe.infrastructure.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * 공개 환경변수가 Spring runtime property에 실제로 연결되는지 검증한다.
 *
 * <p>책임: Compose가 값을 전달해도 application.yaml placeholder가 끊겨 기본값으로
 * 되돌아가는 회귀를 검출한다.</p>
 */
class RuntimeEnvironmentBindingTest {

    /**
     * 비기본 환경 값과 standalone demo seed 기본값을 함께 검증한다.
     *
     * @throws IOException application.yaml을 읽지 못한 경우
     */
    @Test
    void bindsNonDefaultRuntimeEnvironmentAndKeepsDemoSeedOptIn() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OUTCOMEPROBE_DB_PASSWORD", "non-default-db-secret")
                .withProperty("OUTCOMEPROBE_RETENTION_DAYS", "23")
                .withProperty("OUTCOMEPROBE_MAX_CONCURRENT_RUNS", "3")
                .withProperty("OUTCOMEPROBE_EMULATOR_TOKEN", "non-default-emulator-token")
                .withProperty("OUTCOMEPROBE_CONTROL_API_TOKEN",
                        "non-default-control-token-0123456789")
                .withProperty("OUTCOMEPROBE_LOG_LEVEL", "WARN");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("runtime-environment", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo("non-default-db-secret");
        assertThat(environment.getProperty("outcomeprobe.retention.days", Integer.class))
                .isEqualTo(23);
        assertThat(environment.getProperty(
                "outcomeprobe.limits.max-concurrent-runs", Integer.class)).isEqualTo(3);
        assertThat(environment.getProperty("outcomeprobe.emulator.token"))
                .isEqualTo("non-default-emulator-token");
        assertThat(environment.getProperty("outcomeprobe.security.control-api-token"))
                .isEqualTo("non-default-control-token-0123456789");
        assertThat(environment.getProperty("logging.level.io.outcomeprobe"))
                .isEqualTo("WARN");
        assertThat(environment.getProperty(
                "outcomeprobe.demo.profile-seeding-enabled", Boolean.class)).isFalse();
    }
}
