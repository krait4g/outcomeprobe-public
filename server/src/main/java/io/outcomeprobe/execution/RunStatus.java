package io.outcomeprobe.execution;

/**
 * Scenario Run의 생명주기 상태다.
 *
 * <p>책임: Worker lease·재개·판정 로직이 공유하는 상태 vocabulary를 고정한다.</p>
 * <p>핵심 불변식: FAILED와 ERROR는 반드시 구분한다. FAILED는 Target의 업무
 * 상태·상호작용이 기대와 다름을, ERROR는 OutcomeProbe 자체 결함을 의미한다.
 * terminal 상태(PASSED·FAILED·ERROR·CANCELLED)는 다시 실행 상태로 돌아가지 않는다.</p>
 */
public enum RunStatus {

    // 실행 요청이 저장됐으나 Worker가 아직 인수하지 않았다.
    QUEUED,

    // Artifact snapshot·plan·alias lock을 준비 중이다.
    PREPARING,

    // Step을 실행 중이다.
    RUNNING,

    // 모든 action 완료 후 최종 expectation을 확인 중이다.
    ASSERTING,

    // 모든 assertion과 expectation을 충족했다.
    PASSED,

    // Target의 업무 상태 또는 상호작용이 기대와 불일치한다.
    FAILED,

    // OutcomeProbe 자체 오류 또는 잘못된 profile·infra 실패다.
    ERROR,

    // 사용자가 취소했다.
    CANCELLED;

    /**
     * 이 상태가 더 이상 변하지 않는 terminal 상태인지 반환한다.
     *
     * @return terminal이면 true
     */
    public boolean isTerminal() {
        return this == PASSED || this == FAILED || this == ERROR || this == CANCELLED;
    }
}
