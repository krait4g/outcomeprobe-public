package io.outcomeprobe.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * OutcomeProbe 명령줄 도구의 진입점이다.
 *
 * <p>책임: CI와 로컬에서 Scenario 검증·실행·report 수출을 수행하는 명령 체계를 제공한다.
 * M0에서는 help·version만 제공하고, run·validate·import 같은 하위 명령은
 * 마일스톤에 따라 추가된다.</p>
 * <p>핵심 불변식: 종료 코드는 명세의 exit code 계약을 그대로 따른다.
 * Scenario assertion 실패는 1, CLI 입력 오류는 2, 서버·인프라 오류는 3,
 * 사용자 취소는 4로 CI가 기계적으로 판정할 수 있어야 한다.</p>
 */
@Command(
        name = "outcomeprobe",
        mixinStandardHelpOptions = true,
        version = "outcomeprobe 0.1.0",
        description = "외부 연동 실패 시나리오와 최종 상태 수렴을 검증하는 명령줄 도구",
        subcommands = {RunCommand.class, RunFileCommand.class, ImportCommand.class,
                ValidateCommand.class, DoctorCommand.class, ListCommand.class,
                ReportCommand.class, BundleCommand.class, CancelCommand.class})
public final class OutcomeProbeCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    /**
     * 기본 생성자다. 명령 명세는 Picocli가 주입한다.
     */
    public OutcomeProbeCli() {
    }

    /**
     * 하위 명령 없이 호출되면 사용법을 출력하고 정상 종료 코드를 반환한다.
     *
     * <p>사용자가 도구 사용법을 확인하는 행동은 오류가 아니므로 0을 반환한다.</p>
     *
     * @return 정상 종료를 의미하는 0
     */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    /**
     * 프로세스 진입점이다. CLI 실행 결과 종료 코드를 운영체제에 전달한다.
     *
     * @param args 명령행 인수
     */
    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /**
     * 인수 목록으로 CLI를 한 번 실행하고 종료 코드를 반환한다.
     *
     * <p>테스트가 프로세스를 종료하지 않고 결과를 검증할 수 있도록 main과 분리한다.</p>
     *
     * @param args 명령행 인수
     * @return Picocli 실행 결과 종료 코드
     */
    public static int execute(String... args) {
        return new CommandLine(new OutcomeProbeCli()).execute(args);
    }
}
