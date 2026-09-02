package io.outcomeprobe.cli;

import tools.jackson.databind.JsonNode;

/**
 * Run이 terminal에 도달할 때까지 polling하고 결과를 출력한다.
 *
 * <p>책임: --wait 옵션의 대기·요약·종료 코드 결정을 한 곳에 모아 run·run-file
 * 명령이 동일한 보고 규칙을 사용하게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: polling 간격은 고정 500ms다. SSE 기반
 * 실시간 스트리밍은 M3 UI 범위로 CLI는 단순 polling을 사용한다.</p>
 */
public final class RunWaiter {

    private static final long POLL_INTERVAL_MS = 500L;
    private static final long MAX_WAIT_MS = 15L * 60_000L;

    /**
     * 정적 도구 클래스의 인스턴스화를 막는다.
     */
    private RunWaiter() {
    }

    /**
     * terminal까지 대기하고 결과를 출력한 뒤 종료 코드를 반환한다.
     *
     * @param client 서버 client
     * @param runId Run 식별자
     * @param jsonOutput true면 JSON 요약만 출력한다
     * @return 종료 코드
     * @throws InterruptedException 대기 중 인터럽트 시 발생한다
     */
    public static int waitAndReport(ServerClient client, String runId, boolean jsonOutput)
            throws InterruptedException {
        long waited = 0;
        JsonNode run = client.getRun(runId);
        while (!isTerminal(run) && waited < MAX_WAIT_MS) {
            Thread.sleep(POLL_INTERVAL_MS);
            waited += POLL_INTERVAL_MS;
            run = client.getRun(runId);
        }

        String status = run.path("status").asText("ERROR");
        if (jsonOutput) {
            try {
                JsonNode report = client.getReport(runId);
                System.out.println(report.toPrettyString());
            } catch (RuntimeException e) {
                System.out.println(run.toPrettyString());
            }
        } else {
            System.out.println("Run      " + runId);
            System.out.println("Status   " + status);
            if (run.path("problemCode").isTextual()) {
                System.out.println("Problem  " + run.path("problemCode").asText());
            }
        }
        return ExitCodes.fromRunStatus(status);
    }

    /**
     * Run 상태 문자열이 terminal인지 판단한다.
     */
    private static boolean isTerminal(JsonNode run) {
        String status = run.path("status").asText("");
        return switch (status) {
            case "PASSED", "FAILED", "ERROR", "CANCELLED" -> true;
            default -> false;
        };
    }

    /**
     * Run의 JUnit XML report를 파일로 저장한다.
     *
     * <p>왜 필요한가: CI가 OutcomeProbe 결과를 표준 test report로 표시하려면
     * JUnit XML 파일이 필요하기 때문이다.</p>
     *
     * @param client 서버 client
     * @param runId Run 식별자
     * @param target 저장할 파일 경로
     * @throws Exception 저장 실패 시 발생한다
     */
    public static void writeJUnitReport(ServerClient client, String runId,
                                        java.nio.file.Path target) throws Exception {
        String xml = client.getRaw("/api/v1/runs/" + runId + "/reports/junit");
        if (target.getParent() != null) {
            java.nio.file.Files.createDirectories(target.getParent());
        }
        java.nio.file.Files.writeString(target, xml);
        System.out.println("JUnit report 저장 완료: " + target);
    }
}
