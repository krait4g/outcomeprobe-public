package io.outcomeprobe.execution;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.foundation.ProblemCode;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Step 실행기가 반환하는 다음 상태 결정이다.
 *
 * <p>책임: 장시간 대기 없이 완료·대기·실패·내부 오류 중 하나만 반환하게 해
 * Worker가 한 번의 lease에서 무한정 bloque되지 않게 한다.</p>
 * <p>핵심 불변식: Failed는 Target의 업무·상호작용 불일치이고, Errored는
 * OutcomeProbe 자체 결함이다. 이 구분은 Run의 FAILED·ERROR 판정으로 직결된다.</p>
 */
public sealed interface StepExecutionResult {

    /**
     * 외부 호출이 없는 Step 전체를 Run 상태 전이 트랜잭션 안에서 계산하고 저장하도록 지연한다.
     *
     * <p>Callback 계획처럼 계산 자체가 DB 행과 Evidence를 생성하는 경우, 결과 계산과 Step·Run
     * CAS가 서로 다른 트랜잭션으로 갈라지는 것을 막는다. 작업은 중첩 Deferred 또는 Transactional
     * 결과를 반환해서는 안 된다.</p>
     *
     * @param execution 같은 Run 상태 트랜잭션 안에서 실행할 DB-only Step 작업
     */
    record Transactional(Supplier<StepExecutionResult> execution) implements StepExecutionResult {
    }

    /**
     * 외부 호출 뒤 계산된 결과와 DB-only post-result 작업을 RunEngine 트랜잭션까지 지연한다.
     *
     * <p>persistence는 Step·Run CAS와 같은 REQUIRED 트랜잭션에서 먼저 실행되고,
     * afterCommit은 그 트랜잭션이 예외 없이 끝난 뒤에만 호출된다.</p>
     *
     * @param result 실제 Step 상태 결정
     * @param persistence 외부 HTTP를 포함하지 않는 DB 저장 작업
     * @param afterCommit 트랜잭션 성공 뒤 실행할 비영속 후처리
     */
    record Deferred(
            StepExecutionResult result,
            Runnable persistence,
            Runnable afterCommit) implements StepExecutionResult {
    }

    /**
     * Step이 계약을 충족하고 완료됐음을 나타낸다.
     *
     * @param output 후속 Step·Report가 소비할 산출 JSON, 없으면 null
     */
    record Completed(JsonNode output) implements StepExecutionResult {
    }

    /**
     * Step이 외부 관측을 기다리며 thread를 해제함을 나타낸다.
     *
     * @param nextActionAt 다음 재평가 시각
     * @param observation 마지막 관측 snapshot, 없으면 null
     */
    record Waiting(Instant nextActionAt, JsonNode observation) implements StepExecutionResult {
    }

    /**
     * Target의 상태·상호작용이 Scenario 기대와 달라 Step이 실패했음을 나타낸다.
     *
     * @param problemCode 보고할 문제 코드
     * @param detail 사람이 읽는 실패 설명
     * @param detailJson 기계 판독 실패 상세, 없으면 null
     */
    record Failed(ProblemCode problemCode, String detail, JsonNode detailJson) implements StepExecutionResult {
    }

    /**
     * OutcomeProbe 자체 오류로 Step을 진행할 수 없음을 나타낸다.
     *
     * @param problemCode 보고할 문제 코드
     * @param detail 사람이 읽는 오류 설명
     */
    record Errored(ProblemCode problemCode, String detail) implements StepExecutionResult {
    }
}
