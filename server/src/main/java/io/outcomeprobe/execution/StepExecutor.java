package io.outcomeprobe.execution;

/**
 * 하나의 컴파일된 실행 단계를 부작용 계약에 맞게 처리하는 포트다.
 *
 * <p>책임: Step 유형별 실행·재평가 로직을 엔진 루프에서 분리해, 새 Step 유형이
 * 엔진 switch 문이 아니라 독립 실행기로 추가되게 한다.</p>
 * <p>핵심 불변식: 구현체는 장시간 대기하지 않고 완료·대기·실패 중 하나의 다음
 * 상태를 반환해야 한다. 외부 HTTP 호출은 DB transaction 밖에서 수행한다.</p>
 */
public interface StepExecutor {

    /**
     * 이 실행기가 처리할 Step 유형을 반환한다.
     *
     * @return 지원하는 Step 유형
     */
    StepType supports();

    /**
     * 현재 Step의 한 번 실행 또는 재평가를 수행한다.
     *
     * <p>재시도 의미: WAITING을 반환한 Step은 nextActionAt에 다시 lease돼 이 메서드를
     * 다시 호출받는다. 실행기는 attemptCount·lastObservation으로 진행 상태를 이어간다.</p>
     *
     * @param context Run·계획·Step snapshot을 포함한 실행 문맥
     * @return 즉시 완료 여부 또는 다음 재평가 시각을 포함한 결과
     */
    StepExecutionResult execute(StepExecutionContext context);
}
