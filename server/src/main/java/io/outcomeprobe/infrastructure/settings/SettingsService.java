package io.outcomeprobe.infrastructure.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * OutcomeProbe 서버의 실행 중 설정을 모아 Settings 화면에 제공한다.
 *
 * <p>책임: 로컬·CI 전용 워크벤치의 운영 특성(production gateway 아님, telemetry
 * 기본 off, 설정은 환경변수·application.yaml 기반)을 있는 그대로 노출해 사용자가
 * 서버가 어떤 모드로 동작하는지 오해하지 않게 한다.</p>
 * <p>핵심 불변식: 이 서비스는 설정을 읽기만 하며 변경하지 않는다. v1에서 설정
 * 변경은 환경변수·구성 파일과 재기동으로만 반영된다.</p>
 * <p>외부에서 오해하기 쉬운 한계: timeout·body 상한 같은 고정 안전 상수는
 * adapter에 정의돼 있어 여기서 별도로 구성할 수 없다. Settings 화면은 이를
 * "고정 안전 기본값"으로 안내한다.</p>
 */
@Service
public class SettingsService {

    private final String version;
    private final int retentionDays;
    private final int maxConcurrentRuns;
    private final boolean emulatorTokenRequired;

    /**
     * 실행 중 설정 값을 주입한다.
     *
     * @param version 서버 버전 문자열
     * @param retentionDays terminal Run 보존 일수
     * @param maxConcurrentRuns 동시 실행 Run 수 상한
     * @param emulatorToken Emulator ingress token, 비어 있으면 인증 불요
     */
    public SettingsService(
            @Value("${outcomeprobe.version:0.1.0}") String version,
            @Value("${outcomeprobe.retention.days:14}") int retentionDays,
            @Value("${outcomeprobe.limits.max-concurrent-runs:10}") int maxConcurrentRuns,
            @Value("${outcomeprobe.emulator.token:}") String emulatorToken) {
        this.version = version;
        this.retentionDays = retentionDays;
        this.maxConcurrentRuns = maxConcurrentRuns;
        // token이 공백이면 Emulator가 인증 없이 열려 있다는 뜻이므로 false로 보고한다.
        this.emulatorTokenRequired = emulatorToken != null && !emulatorToken.isBlank();
    }

    /**
     * Settings 화면이 표시할 실행 중 설정을 반환한다.
     *
     * @return 버전·동작 모드·보존 기간·동시 실행 상한·telemetry 여부 등 설정 집합
     */
    public Map<String, Object> settings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("dslVersions", List.of("outcomeprobe.io/v1alpha1"));
        result.put("mode", "local-test-workbench");
        result.put("storage", "postgresql");
        result.put("productionGateway", false);
        result.put("retentionDays", retentionDays);
        result.put("maxConcurrentRuns", maxConcurrentRuns);
        // 필수 token 값·길이·digest는 노출하지 않고 활성화 사실만 고정 계약으로 보고한다.
        result.put("controlApiAuthenticationRequired", true);
        result.put("emulatorTokenRequired", emulatorTokenRequired);
        result.put("telemetryEnabled", false);
        result.put("telemetryNote",
                "OutcomeProbe는 사용 통계를 외부로 자동 전송하지 않습니다. Telemetry는 기본 off입니다.");
        return result;
    }
}
