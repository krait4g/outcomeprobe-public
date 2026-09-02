package io.outcomeprobe.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/**
 * Scenario·Run·Target Profile 목록을 조회하는 {@code list} 명령이다.
 *
 * <p>책임: CI·로컬에서 등록된 자원을 빠르게 조회하게 한다. 서버 GET API를
 * 호출해 결과를 출력한다.</p>
 */
@Command(
        name = "list",
        description = "Scenario·Run·Profile 목록을 조회한다",
        mixinStandardHelpOptions = true)
public class ListCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "조회 대상: scenarios | runs | profiles")
    private String target;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public ListCommand() {
    }

    /**
     * 지정한 대상의 목록을 조회해 출력한다.
     *
     * @return 종료 코드 (0=성공, 2=잘못된 대상, 3=서버 오류)
     */
    @Override
    public Integer call() {
        try {
            ServerClient client = connectionOptions.createClient();
            JsonNode result = switch (target) {
                case "scenarios" -> client.listScenarios();
                case "runs" -> client.listRuns();
                case "profiles" -> client.listProfiles();
                default -> null;
            };
            if (result == null) {
                System.err.println("알 수 없는 조회 대상입니다: " + target
                        + " (scenarios | runs | profiles)");
                return ExitCodes.INPUT_ERROR;
            }
            printList(result);
            return ExitCodes.ALL_PASSED;
        } catch (ServerClient.CliRequestException e) {
            System.err.println("조회 실패: " + e.getMessage());
            return ExitCodes.SERVER_ERROR;
        } catch (CliConfigurationException e) {
            System.err.println("CLI 설정 오류: " + e.getMessage());
            return ExitCodes.INPUT_ERROR;
        }
    }

    /**
     * 조회 결과 배열을 사람 읽는 형태로 출력한다.
     */
    private void printList(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            System.out.println("(비어 있음)");
            return;
        }
        for (JsonNode item : array) {
            if (item.has("runId")) {
                System.out.printf("%s  %-10s  %s  %s%n",
                        item.path("runId").asText(),
                        item.path("status").asText(),
                        item.path("scenario").asText(),
                        item.path("targetProfile").asText());
            } else {
                System.out.printf("%s@%s  %s%n",
                        item.path("name").asText(),
                        item.path("version").asText(),
                        item.path("displayName").asText());
            }
        }
    }

}
