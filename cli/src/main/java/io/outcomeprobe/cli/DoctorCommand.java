package io.outcomeprobe.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 서버·Target 연결 상태를 진단하는 {@code doctor} 명령이다.
 *
 * <p>책임: server 연결·버전·health·Target Profile·secret reference 존재 여부를
 * 확인해 "왜 실행이 안 되는가"를 빠르게 분리한다. 실제 Scenario는 실행하지 않는다.</p>
 * <p>핵심 불변식: secret 값은 조회하지 않고 reference 존재 여부만 확인한다.</p>
 */
@Command(
        name = "doctor",
        description = "서버·Target 연결 상태를 진단한다",
        mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

    @Option(names = "--target", description = "연결 사전검사를 실행할 Target Profile 이름")
    private String targetProfile;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public DoctorCommand() {
    }

    /**
     * 진단을 실행하고 항목별 결과를 출력한다.
     *
     * @return 종료 코드 (0=정상, 3=서버 unreachable)
     */
    @Override
    public Integer call() {
        ServerClient client;
        try {
            client = connectionOptions.createClient();
        } catch (CliConfigurationException e) {
            System.err.println("[FAIL] CLI 설정 오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        }
        System.out.println("server: " + connectionOptions.resolveServerUrl());
        System.out.println("[OK] Control API Bearer token 설정됨");
        try {
            var health = client.getRaw("/actuator/health/readiness");
            System.out.println("[OK] readiness: " + health);
        } catch (Exception e) {
            System.err.println("[FAIL] 서버에 연결할 수 없습니다: " + e.getMessage());
            return ExitCodes.SERVER_ERROR;
        }
        try {
            var info = client.getRaw("/actuator/info");
            System.out.println("[OK] info: " + info.replaceAll("\\s+", " "));
        } catch (Exception e) {
            System.err.println("[WARN] info 조회 실패: " + e.getMessage());
        }
        try {
            var profiles = client.getRaw("/api/v1/artifacts?kind=TARGET_PROFILE");
            long count = profiles.trim().isEmpty() || "[]".equals(profiles.trim())
                    ? 0 : profiles.split("\\{\"name\"").length - 1;
            System.out.println("[OK] Target Profile " + count + "개 등록됨");
        } catch (Exception e) {
            System.err.println("[WARN] Profile 조회 실패: " + e.getMessage());
        }
        if (targetProfile != null) {
            try {
                var preflight = client.verifyTargetProfile(targetProfile);
                if (!preflight.path("passed").asBoolean(false)) {
                    System.err.println("[FAIL] Target Profile 연결 사전검사가 실패했습니다: "
                            + targetProfile);
                    return ExitCodes.SERVER_ERROR;
                }
                System.out.println("[OK] Target Profile 연결 사전검사: " + targetProfile);
            } catch (IllegalArgumentException e) {
                System.err.println("[FAIL] " + e.getMessage());
                return ExitCodes.INPUT_ERROR;
            } catch (ServerClient.CliRequestException e) {
                System.err.println("[FAIL] Target Profile 연결 사전검사를 완료할 수 없습니다: "
                        + e.getMessage());
                return e.statusCode() >= 400 && e.statusCode() < 500
                        ? ExitCodes.INPUT_ERROR : ExitCodes.SERVER_ERROR;
            }
        }
        try {
            var scenarios = client.getRaw("/api/v1/artifacts?kind=SCENARIO");
            long count = scenarios.trim().isEmpty() || "[]".equals(scenarios.trim())
                    ? 0 : scenarios.split("\\{\"kind\"").length - 1;
            System.out.println("[OK] Scenario " + count + "개 등록됨");
        } catch (Exception e) {
            System.err.println("[WARN] Scenario 조회 실패: " + e.getMessage());
        }
        System.out.println("진단 완료.");
        return ExitCodes.ALL_PASSED;
    }
}
