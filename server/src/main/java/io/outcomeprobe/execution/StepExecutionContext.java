package io.outcomeprobe.execution;

/**
 * Step 실행 한 번에 필요한 문맥을 묶는다.
 *
 * <p>책임: 실행기가 Run·계획·현재 Step snapshot만으로 부작용 계약을 판단하게 해
 * 엔진 외 전역 상태를 참조하지 못하게 한다.</p>
 *
 * @param run 현재 Run snapshot
 * @param plan 컴파일된 실행 계획
 * @param step 실행 대상 Step snapshot
 */
public record StepExecutionContext(ScenarioRun run, CompiledRunPlan plan, RunStepExecution step) {
}
