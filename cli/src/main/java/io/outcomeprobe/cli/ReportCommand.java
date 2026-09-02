package io.outcomeprobe.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Run report를 내려받는 {@code report} 명령이다.
 *
 * <p>책임: json·html·junit 형식의 report를 파일로 저장해 CI artifact나
 * 공유용으로 쓰게 한다.</p>
 */
@Command(
        name = "report",
        description = "Run report를 파일로 저장한다",
        mixinStandardHelpOptions = true)
public class ReportCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Run 식별자")
    private String runId;

    @Option(names = "--format", description = "report 형식: json | html | junit (기본 json)")
    private String format = "json";

    @Option(names = "--out", description = "저장할 파일 경로 (기본 stdout)")
    private Path out;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public ReportCommand() {
    }

    /**
     * report를 조회해 stdout 또는 파일로 출력한다.
     *
     * @return 종료 코드 (0=성공, 2=잘못된 형식, 3=서버 오류)
     */
    @Override
    public Integer call() {
        try {
            ServerClient client = connectionOptions.createClient();
            String content = switch (format) {
                case "json" -> client.getReport(runId).toString();
                case "html" -> client.getReportHtml(runId);
                case "junit" -> client.getReportJunit(runId);
                default -> null;
            };
            if (content == null) {
                System.err.println("알 수 없는 report 형식입니다: " + format
                        + " (json | html | junit)");
                return ExitCodes.INPUT_ERROR;
            }
            if (out != null) {
                Files.writeString(out, content);
                System.out.println("report 저장: " + out);
            } else {
                System.out.println(content);
            }
            return ExitCodes.ALL_PASSED;
        } catch (ServerClient.CliRequestException e) {
            System.err.println("report 조회 실패: " + e.getMessage());
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
