package io.outcomeprobe.execution;

import java.util.List;

/**
 * Run Step 영속화를 위한 outbound Port다.
 *
 * <p>책임: RunEngine이 persistence 구현(JDBC)에 직접 의존하지 않고
 * Hexagonal Architecture의 port-adapter 분리를 유지하게 한다.</p>
 * <p>핵심 불변식: 갱신은 optimistic version으로 보호돼 lease를 잃은 Worker의
 * 지연된 쓰기가 현재 소유자의 상태를 덮어쓰지 못한다.</p>
 */
public interface RunStepPort {

    /**
     * Step 행을 하나 저장한다.
     *
     * @param step 저장할 Step 실행
     */
    void save(RunStepExecution step);

    /**
     * Run의 모든 Step을 ordinal 정렬로 조회한다.
     *
     * @param runId Run 식별자
     * @return Step 목록
     */
    List<RunStepExecution> findByRunId(String runId);

    /**
     * Step 상태를 optimistic version으로 갱신한다.
     *
     * @param step 갱신할 Step (version 포함)
     * @return CAS 성공 여부
     */
    boolean update(RunStepExecution step);
}
