package io.outcomeprobe.execution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Scenario Run 영속화를 위한 outbound Port다.
 *
 * <p>책임: RunEngine이 persistence 구현(JDBC)에 직접 의존하지 않고
 * Hexagonal Architecture의 port-adapter 분리를 유지하게 한다.</p>
 * <p>핵심 불변식: lease는 FOR UPDATE SKIP LOCKED 기반으로 두 Worker가
 * 같은 Run을 동시에 처리하지 못하게 한다.</p>
 */
public interface ScenarioRunPort {

    /**
     * 새 Run을 저장한다.
     *
     * @param run 저장할 Run
     */
    void save(ScenarioRun run);

    /**
     * Run을 식별자로 조회한다.
     *
     * @param id Run 식별자
     * @return 존재하면 Run
     */
    Optional<ScenarioRun> findById(String id);

    /**
     * 기한이 도래한 Run 하나를 lease한다.
     *
     * @param workerId lease 소유자 식별자
     * @param now 현재 시각
     * @param leaseDurationMs lease 유효 기간(ms)
     * @return lease에 성공하면 Run
     */
    Optional<ScenarioRun> leaseNextDueRun(String workerId, Instant now, long leaseDurationMs);

    /**
     * Run 상태를 optimistic version으로 갱신한다.
     *
     * @param run 갱신할 Run (version 포함)
     * @return CAS 성공 여부
     */
    boolean update(ScenarioRun run);

    /**
     * 활성(terminal 아닌) Run 수를 반환한다.
     *
     * @return 활성 Run 수
     */
    long countActive();

    /**
     * 최근 Run을 생성 시각 역순으로 조회한다.
     *
     * @param limit 최대 조회 건수
     * @return Run 목록
     */
    List<ScenarioRun> findRecent(int limit);
}
