package io.outcomeprobe.execution;

/**
 * Run Step의 생명주기 상태다.
 *
 * <p>책임: WAITING Step이 thread를 점유하지 않고 nextActionAt 기반으로
 * 재개되는 durable 실행 모델을 상태 vocabulary로 표현한다.</p>
 * <p>핵심 불변식: WAITING은 활성 상태다. deadline과 nextActionAt을 저장하고
 * Worker가 다시 lease해 재평가한다.</p>
 */
public enum StepStatus {

    // 아직 실행되지 않았다.
    PENDING,

    // 실행 중이다.
    RUNNING,

    // 외부 관측을 기다리며 thread를 점유하지 않는다.
    WAITING,

    // Step 계약이 충족됐다.
    PASSED,

    // Target 상태 또는 assertion이 기대와 다르다.
    FAILED,

    // OutcomeProbe 내부 오류 또는 허용되지 않은 구성이다.
    ERROR,

    // Run deadline·취소로 실행되지 않고 건너뛰었다.
    SKIPPED,

    // 사용자 취소로 중단됐다.
    CANCELLED;

    /**
     * 이 상태가 더 이상 변하지 않는 terminal 상태인지 반환한다.
     *
     * @return terminal이면 true
     */
    public boolean isTerminal() {
        return this == PASSED || this == FAILED || this == ERROR || this == SKIPPED || this == CANCELLED;
    }
}
