package io.outcomeprobe.cli;

/**
 * CLI 종료 코드 계약이다.
 *
 * <p>책임: CI가 Scenario 결과를 기계적으로 판정할 수 있게 고정된 코드를 정의한다.
 * 이 계약은 명세의 exit code 표와 일치해야 한다.</p>
 * <p>핵심 불변식: Scenario assertion 실패(1)와 OutcomeProbe 내부 오류(3)는
 * 서로 다른 코드다. 실패를 도구 오류로 숨기지 않는다.</p>
 */
public final class ExitCodes {

    /**
     * 모든 Scenario가 PASS한 코드다.
     */
    public static final int ALL_PASSED = 0;

    /**
     * Scenario assertion FAIL 코드다.
     */
    public static final int SCENARIO_FAILED = 1;

    /**
     * CLI·DSL 입력 오류 코드다.
     */
    public static final int INPUT_ERROR = 2;

    /**
     * OutcomeProbe 서버·인프라 ERROR 코드다.
     */
    public static final int SERVER_ERROR = 3;

    /**
     * 사용자 취소 코드다.
     */
    public static final int CANCELLED = 4;

    /**
     * 상수 전용 클래스의 인스턴스화를 막는다.
     */
    private ExitCodes() {
    }

    /**
     * Run terminal 상태를 종료 코드로 변환한다.
     *
     * @param status Run 상태 문자열
     * @return 대응하는 종료 코드
     */
    public static int fromRunStatus(String status) {
        return switch (status) {
            case "PASSED" -> ALL_PASSED;
            case "FAILED" -> SCENARIO_FAILED;
            case "CANCELLED" -> CANCELLED;
            default -> SERVER_ERROR;
        };
    }
}
