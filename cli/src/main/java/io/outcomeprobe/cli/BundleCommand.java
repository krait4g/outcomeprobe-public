package io.outcomeprobe.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Run 전체 증거 bundle(zip)을 내려받는 {@code bundle} 명령이다.
 *
 * <p>책임: Scenario·계획·report·Timeline을 묶은 zip을 저장해 장애 재현·공유에
 * 쓰게 한다. secret은 서버 단계에서 이미 redaction돼 있다.</p>
 */
@Command(
        name = "bundle",
        description = "Run 증거 bundle(zip)을 저장한다",
        mixinStandardHelpOptions = true)
public class BundleCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Run 식별자")
    private String runId;

    @Option(names = "--out", description = "저장할 zip 파일 경로 (기본 <runId>-bundle.zip)")
    private Path out;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public BundleCommand() {
    }

    /**
     * bundle을 내려받아 파일로 저장한다.
     *
     * @return 종료 코드 (0=성공, 3=서버 오류)
     */
    @Override
    public Integer call() {
        try {
            ServerClient client = connectionOptions.createClient();
            byte[] bundle = client.getBundle(runId);
            Path target = out != null ? out : Path.of(runId + "-bundle.zip");
            Files.write(target, bundle);
            System.out.println("bundle 저장: " + target + " (" + bundle.length + " bytes)");
            return ExitCodes.ALL_PASSED;
        } catch (ServerClient.CliRequestException e) {
            System.err.println("bundle 조회 실패: " + e.getMessage());
            return ExitCodes.SERVER_ERROR;
        } catch (CliConfigurationException e) {
            System.err.println("CLI 설정 오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        } catch (Exception e) {
            System.err.println("오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        }
    }
}
