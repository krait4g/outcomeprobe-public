package io.outcomeprobe.infrastructure.settings;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings 화면에 서버 실행 중 설정을 제공하는 Control API다.
 *
 * <p>책임: UI가 서버의 동작 모드·보존 기간·동시 실행 상한·telemetry 여부를
 * 읽어 표시하게 한다. 읽기 전용이며 설정 변경은 제공하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settingsService;

    /**
     * 설정 서비스를 주입한다.
     *
     * @param settingsService 실행 중 설정 집계 서비스
     */
    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * 서버 실행 중 설정을 조회한다.
     *
     * @return 설정 집합
     */
    @GetMapping
    public Map<String, Object> settings() {
        return settingsService.settings();
    }
}
