package io.outcomeprobe.execution;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Scenario 실행 하나의 현재 상태를 나타내는 Aggregate root다.
 *
 * <p>책임: Run 진행 상태·현재 step·deadline·lease·결정론 seed를 한 곳에 모아
 * Worker가 durable하게 Run을 인수·재개·판정하게 한다.</p>
 * <p>핵심 불변식: 하나의 Run은 동시에 하나의 Worker만 lease한다. lease는
 * DB 조건과 version으로 보호되며 JVM-local lock에 의존하지 않는다. terminal
 * Run은 다시 실행 상태로 전이되지 않는다.</p>
 *
 * @param id Run 식별자
 * @param scenarioName snapshot scenario 이름
 * @param scenarioVersion snapshot scenario 버전
 * @param scenarioHash snapshot scenario content hash
 * @param targetProfileName 선택된 Target Profile 이름
 * @param seed 결정론 seed
 * @param status 현재 Run 상태
 * @param currentStepOrdinal 현재 실행 중인 step 순서, 준비 전엔 null
 * @param compiledPlan immutable 실행 계획 JSON
 * @param nextActionAt Worker가 이 Run을 다시 평가할 시각
 * @param deadlineAt Run 전체 마감 시각
 * @param leaseOwner 현재 lease 소유 Worker, 없으면 null
 * @param leaseUntil lease 만료 시각
 * @param version optimistic version
 * @param nextEvidenceSequence evidence sequence 할당기 값
 * @param cancelRequested 취소 요청 여부
 * @param problemCode 실패·오류 코드, 없으면 null
 * @param problemDetail redaction된 문제 상세, 없으면 null
 * @param startedAt 실행 시작 시각
 * @param finishedAt terminal 전환 시각
 * @param createdAt 생성 시각
 */
public record ScenarioRun(
        String id,
        String scenarioName,
        String scenarioVersion,
        String scenarioHash,
        String targetProfileName,
        long seed,
        RunStatus status,
        Integer currentStepOrdinal,
        JsonNode compiledPlan,
        Instant nextActionAt,
        Instant deadlineAt,
        String leaseOwner,
        Instant leaseUntil,
        long version,
        long nextEvidenceSequence,
        boolean cancelRequested,
        String problemCode,
        JsonNode problemDetail,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {
}
