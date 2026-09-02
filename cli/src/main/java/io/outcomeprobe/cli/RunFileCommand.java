package io.outcomeprobe.cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * 로컬 Scenario YAML을 검증·import하고 실행하는 {@code run-file} 명령이다.
 *
 * <p>책임: 파일 하나를 읽어 content hash 기반으로 재사용 import한 뒤 Run을
 * 생성한다. 사용자가 서버에 수동 import하지 않아도 CI에서 바로 실행할 수 있다.</p>
 * <p>핵심 불변식: 파일 경로 traversal은 CLI 인수로 주어진 경로를 그대로 사용할
 * 뿐 서버에는 YAML 내용만 전송된다. secret literal은 서버 검증에서 거부된다.</p>
 */
@Command(
        name = "run-file",
        description = "로컬 Scenario 파일로 Run을 실행한다",
        mixinStandardHelpOptions = true)
public class RunFileCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Scenario YAML 파일 경로")
    private Path scenarioFile;

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
    public RunFileCommand() {
    }

    /**
     * 파일을 읽어 import하고 Run을 생성·대기한다.
     *
     * @return 종료 코드
     */
    @Override
    public Integer call() {
        try {
            if (scenarioFile == null || !Files.isRegularFile(scenarioFile)) {
                System.err.println("Scenario 파일을 읽을 수 없습니다: " + scenarioFile);
                return ExitCodes.INPUT_ERROR;
            }
            String yaml = Files.readString(scenarioFile);

            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            JsonNode document = yamlMapper.readTree(yaml);
            String name = document.path("metadata").path("name").asText(null);
            String version = document.path("metadata").path("version").asText(null);
            String kind = document.path("kind").asText(null);
            if (name == null || version == null || !"Scenario".equals(kind)) {
                System.err.println("파일은 kind: Scenario와 metadata.name·version을 포함해야 합니다.");
                return ExitCodes.INPUT_ERROR;
            }

            ServerClient client = connectionOptions.createClient();
            client.importArtifact(yaml);

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.createObjectNode();
            ObjectNode scenarioRef = body.putObject("scenarioRef");
            scenarioRef.put("name", name);
            scenarioRef.put("version", version);
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
            if (junitFile != null) {
                try {
                    RunWaiter.writeJUnitReport(client, runId, junitFile);
                } catch (Exception e) {
                    System.err.println("JUnit report 저장 실패: " + e.getMessage());
                }
            }
            return exitCode;
        } catch (ServerClient.CliRequestException e) {
            String message = e.getMessage();
            if (message != null && message.contains("SCENARIO_REFERENCE_NOT_FOUND")
                    && message.contains("ProviderContract")) {
                System.err.println("ProviderContract가 등록되지 않았습니다.");
                System.err.println("먼저 outcomeprobe import <contract.yaml>을 실행하세요.");
            } else if (message != null && message.contains("TARGET_PROFILE_NOT_FOUND")) {
                System.err.println("TargetProfile이 등록되지 않았습니다.");
                System.err.println("먼저 outcomeprobe import <profile.yaml>을 실행하세요.");
            } else {
                System.err.println("요청 실패: " + message);
            }
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
