package io.outcomeprobe.cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 서버에 등록된 Scenario를 실행하는 {@code run} 명령이다.
 *
 * <p>책임: Scenario 참조·Target·seed·변수를 받아 Run을 생성하고, {@code --wait}
 * 시 terminal까지 대기한 뒤 종료 코드로 결과를 보고한다.</p>
 * <p>핵심 불변식: 종료 코드는 {@link ExitCodes} 계약을 정확히 따른다. CI는
 * 이 코드만으로 PASS·FAIL·도구 오류를 구분할 수 있어야 한다.</p>
 */
@Command(
        name = "run",
        description = "서버에 등록된 Scenario를 실행한다",
        mixinStandardHelpOptions = true)
public class RunCommand implements Callable<Integer> {

    @Option(names = "--scenario", required = true,
            description = "Scenario 이름@버전 (예: payment-timeout-after-commit@1.0.0)")
    private String scenario;

    @Option(names = "--target", description = "Target Profile 이름")
    private String target;

    @Option(names = "--seed", description = "결정론 seed")
    private Long seed;

    @Option(names = "--var", description = "변수 override (key=value), 반복 가능")
    private Map<String, String> variables = new LinkedHashMap<>();

    @Option(names = "--wait", description = "Run이 끝날 때까지 대기한다")
    private boolean wait;

    @Option(names = "--output", description = "출력 형식: human | json", defaultValue = "human")
    private String output;

    @Mixin
    private CliConnectionOptions connectionOptions = new CliConnectionOptions();

    @Option(names = "--junit", description = "JUnit XML report를 저장할 파일 경로")
    private java.nio.file.Path junitFile;

    /**
     * 기본 생성자다. 옵션은 Picocli가 주입한다.
     */
    public RunCommand() {
    }

    /**
     * Run을 생성하고 대기 옵션에 따라 결과를 보고한다.
     *
     * @return 종료 코드
     */
    @Override
    public Integer call() {
        String atSeparator = scenario == null ? null : scenario;
        if (atSeparator == null || !atSeparator.contains("@")) {
            System.err.println("--scenario는 name@version 형식이어야 합니다.");
            return ExitCodes.INPUT_ERROR;
        }
        String[] parts = atSeparator.split("@", 2);

        try {
            ServerClient client = connectionOptions.createClient();
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.createObjectNode();
            ObjectNode scenarioRef = body.putObject("scenarioRef");
            scenarioRef.put("name", parts[0]);
            scenarioRef.put("version", parts[1]);
            if (target != null) {
                body.put("targetProfile", target);
            }
            if (seed != null) {
                body.put("seed", seed);
            }
            if (!variables.isEmpty()) {
                ObjectNode vars = body.putObject("variables");
                variables.forEach(vars::put);
            }

            JsonNode created = client.createRun(mapper.writeValueAsString(body));
            String runId = created.path("runId").asText();

            if (!wait) {
                System.out.println("Run 접수가 완료됐습니다: " + runId);
                return ExitCodes.ALL_PASSED;
            }
            int exitCode = RunWaiter.waitAndReport(client, runId, "json".equalsIgnoreCase(output));
            writeJUnitIfRequested(client, runId);
            return exitCode;
        } catch (ServerClient.CliRequestException e) {
            System.err.println("요청 실패: " + e.getMessage());
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

    /**
     * --junit 옵션이 있으면 JUnit XML을 파일로 저장한다.
     *
     * @param client 서버 client
     * @param runId Run 식별자
     */
    public void writeJUnitIfRequested(ServerClient client, String runId) {
        if (junitFile == null) {
            return;
        }
        try {
            RunWaiter.writeJUnitReport(client, runId, junitFile);
        } catch (Exception e) {
            System.err.println("JUnit report 저장 실패: " + e.getMessage());
        }
    }

}
