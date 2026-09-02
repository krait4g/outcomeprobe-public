package io.outcomeprobe.execution;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Run 안에서 Step 하나의 실행 상태를 나타낸다.
 *
 * <p>책임: WAITING Step의 nextActionAt·deadline·마지막 관측을 보존해 Worker가
 * thread를 점유하지 않고 단기간 lease로 재평가하게 한다.</p>
 * <p>핵심 불변식: 같은 Step은 effect key 없이 side effect를 중복 실행하지 않는다.
 * 실행기는 한 번의 lease에서 완료·대기·실패 중 하나만 반환한다.</p>
 *
 * @param id 행 식별자
 * @param runId 소속 Run 식별자
 * @param stepId Scenario Step 식별자
 * @param ordinal 실행 순서
 * @param stepType Step 유형
 * @param status 현재 Step 상태
 * @param inputSnapshot 컴파일된 Step 입력 JSON
 * @param outputSnapshot Step 산출 JSON, 없으면 null
 * @param waitStartedAt WAITING 진입 시각
 * @param nextActionAt 다음 재평가 시각
 * @param deadlineAt Step 마감 시각
 * @param attemptCount 실행·재평가 시도 횟수
 * @param lastObservation 마지막 관측 JSON, 없으면 null
 * @param problemCode 실패·오류 코드, 없으면 null
 * @param version optimistic version
 * @param startedAt 최초 실행 시각
 * @param finishedAt terminal 전환 시각
 */
public record RunStepExecution(
        String id,
        String runId,
        String stepId,
        int ordinal,
        StepType stepType,
        StepStatus status,
        JsonNode inputSnapshot,
        JsonNode outputSnapshot,
        Instant waitStartedAt,
        Instant nextActionAt,
        Instant deadlineAt,
        int attemptCount,
        JsonNode lastObservation,
        String problemCode,
        long version,
        Instant startedAt,
        Instant finishedAt) {
}
