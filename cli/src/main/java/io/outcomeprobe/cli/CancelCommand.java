package io.outcomeprobe.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * 진행 중인 Run을 취소하는 {@code cancel} 명령이다.
 *
 * <p>책임: 서버에 취소 요청을 전달한다. 취소는 안전 경계에서 반영되며 이미
 * 외부로 나간 요청은 되돌리지 않는다.</p>
 */
@Command(
        name = "cancel",
        description = "진행 중인 Run을 취소한다",
        mixinStandardHelpOptions = true)
public class CancelCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Run 식별자")
    private String runId;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public CancelCommand() {
    }

    /**
     * 서버에 취소 요청을 보낸다.
     *
     * @return 종료 코드 (0=성공, 3=서버 오류)
     */
    @Override
    public Integer call() {
        try {
            ServerClient client = connectionOptions.createClient();
            var result = client.cancelRun(runId);
            boolean requested = result.path("requested").asBoolean(false);
            System.out.println(requested
                    ? "취소 요청됨: " + runId
                    : "취소 요청이 거부됨 (이미 종료됐거나 존재하지 않음): " + runId);
            return ExitCodes.ALL_PASSED;
        } catch (ServerClient.CliRequestException e) {
            System.err.println("취소 실패: " + e.getMessage());
            return ExitCodes.SERVER_ERROR;
        } catch (CliConfigurationException e) {
            System.err.println("CLI 설정 오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        }
    }
}
