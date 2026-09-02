package io.outcomeprobe.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * Artifact YAML을 서버 validation core로 검증하는 {@code validate} 명령이다.
 *
 * <p>책임: import 전에 문법·참조·보안 검사를 실행해 오류를 조기 발견한다.
 * 서버의 `/api/v1/artifacts/validate`와 동일한 validation core를 사용하므로
 * 로컬 통과 후 import 실패가 거의 없다.</p>
 */
@Command(
        name = "validate",
        description = "Artifact YAML을 검증한다",
        mixinStandardHelpOptions = true)
public class ValidateCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "검증할 YAML 파일 경로")
    private Path file;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public ValidateCommand() {
    }

    /**
     * 파일을 읽어 서버 validation core로 검증한다.
     *
     * @return 종료 코드 (0=valid, 2=invalid·입력 오류, 3=서버 오류)
     */
    @Override
    public Integer call() {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                System.err.println("파일을 읽을 수 없습니다: " + file);
                return ExitCodes.INPUT_ERROR;
            }
            String yaml = Files.readString(file);
            ServerClient client = connectionOptions.createClient();
            var result = client.validate(yaml);
            System.out.println("검증 성공: " + result.path("kind").asText() + " "
                    + result.path("name").asText() + "@" + result.path("version").asText());
            return ExitCodes.ALL_PASSED;
        } catch (ServerClient.CliRequestException e) {
            System.err.println("검증 실패: " + e.getMessage());
            return e.statusCode() >= 400 && e.statusCode() < 500
                    ? ExitCodes.INPUT_ERROR
                    : ExitCodes.SERVER_ERROR;
        } catch (CliConfigurationException e) {
            System.err.println("CLI 설정 오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        } catch (Exception e) {
            System.err.println("오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        }
    }

}
