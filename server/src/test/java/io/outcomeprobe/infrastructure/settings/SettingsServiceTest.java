package io.outcomeprobe.infrastructure.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SettingsService}가 노출하는 실행 중 설정을 검증한다.
 *
 * <p>검출 가능한 결함: 서버가 자신을 production gateway로 잘못 보고하거나,
 * telemetry가 기본 on으로 뒤집히거나, token이 비어 있는데 Emulator 인증이
 * 켜진 것으로 보고되는 문제.</p>
 */
class SettingsServiceTest {

    /**
     * 기본 설정에서 동작 모드·telemetry off·productionGateway=false가 항상 보고돼야 한다.
     *
     * <p>이 값들은 v1의 보안· 프라이버시 경계 주장 근거이므로 회귀하면 빌드가 실패해야 한다.</p>
     */
    @Test
    void reportsLocalWorkbenchDefaults() {
        SettingsService service = new SettingsService("0.1.0", 14, 10, "");

        Map<String, Object> settings = service.settings();

        assertThat(settings)
                .containsEntry("version", "0.1.0")
                .containsEntry("mode", "local-test-workbench")
                .containsEntry("storage", "postgresql")
                .containsEntry("productionGateway", false)
                .containsEntry("retentionDays", 14)
                .containsEntry("maxConcurrentRuns", 10)
                .containsEntry("controlApiAuthenticationRequired", true)
                .containsEntry("telemetryEnabled", false);
    }

    /**
     * Emulator token이 공백이면 인증 불요로, 값이 있으면 인증 요구로 보고해야 한다.
     */
    @Test
    void reportsEmulatorTokenRequirement() {
        assertThat(new SettingsService("0.1.0", 14, 10, "").settings())
                .containsEntry("emulatorTokenRequired", false);
        assertThat(new SettingsService("0.1.0", 14, 10, "   ").settings())
                .containsEntry("emulatorTokenRequired", false);
        assertThat(new SettingsService("0.1.0", 14, 10, "secret-token").settings())
                .containsEntry("emulatorTokenRequired", true);
    }
}
