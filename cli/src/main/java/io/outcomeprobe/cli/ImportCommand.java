package io.outcomeprobe.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * 단일 Artifact YAML 파일을 서버로 import하는 명령이다.
 *
 * <p>책임: 사용자가 지정한 regular file 하나만 읽어 Artifact import API에 전달한다.</p>
 * <p>핵심 불변식: 디렉터리나 특수 파일은 네트워크 요청 전에 입력 오류로 거부한다.</p>
 */
@Command(
        name = "import",
        description = "단일 Artifact YAML 파일을 import한다",
        mixinStandardHelpOptions = true)
public class ImportCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Artifact YAML 파일 경로")
    private Path source;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public ImportCommand() {
    }

    /**
     * regular YAML 파일 하나를 읽어 서버에 import한다.
     *
     * @return 종료 코드
     */
    @Override
    public Integer call() {
        try {
            if (source == null || !Files.isRegularFile(source)) {
                System.err.println("Artifact YAML 파일을 찾을 수 없습니다: " + source);
                return ExitCodes.INPUT_ERROR;
            }
            ServerClient client = connectionOptions.createClient();
            client.importArtifact(Files.readString(source));
            System.out.println("Artifact import 완료: " + source);
            return ExitCodes.ALL_PASSED;
        } catch (ServerClient.CliRequestException e) {
            System.err.println("import 실패: " + e.getMessage());
            return e.statusCode() >= 400 && e.statusCode() < 500
                    ? ExitCodes.INPUT_ERROR
                    : ExitCodes.SERVER_ERROR;
        } catch (CliConfigurationException e) {
            System.err.println("CLI 설정 오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        } catch (Exception e) {
            System.err.println("CLI 오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        }
    }

}
