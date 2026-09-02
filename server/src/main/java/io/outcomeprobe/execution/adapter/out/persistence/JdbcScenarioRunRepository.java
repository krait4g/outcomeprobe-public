package io.outcomeprobe.execution.adapter.out.persistence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.ScenarioRunPort;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * scenario_run 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: durable Run 저장, FOR UPDATE SKIP LOCKED 기반 lease, optimistic
 * version 상태 갱신을 제공한다.</p>
 * <p>핵심 불변식: due Run lease는 단일 트랜잭션에서 행 잠금과 lease 갱신을
 * 완료해 두 Worker가 같은 Run을 동시에 처리하지 못하게 한다.</p>
 */
@Repository
public class JdbcScenarioRunRepository implements ScenarioRunPort {

    private final JdbcClient jdbcClient;
    private final JsonbSupport jsonb;

    /**
     * Run 저장에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param objectMapper JSONB 변환용 mapper
     */
    public JdbcScenarioRunRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * 새 Run을 저장한다.
     *
     * @param run 저장할 Run
     */
    public void save(ScenarioRun run) {
        jdbcClient.sql("""
                        INSERT INTO scenario_run (
                            id, scenario_name, scenario_version, scenario_hash, target_profile_name,
                            seed, status, current_step_ordinal, compiled_plan, next_action_at, deadline_at,
                            lease_owner, lease_until, version, next_evidence_sequence, cancel_requested,
                            problem_code, problem_detail, started_at, finished_at, created_at
                        ) VALUES (
                            :id, :scenarioName, :scenarioVersion, :scenarioHash, :targetProfileName,
                            :seed, :status, :currentStepOrdinal, :compiledPlan, :nextActionAt, :deadlineAt,
                            NULL, NULL, 0, 0, false,
                            NULL, NULL, :startedAt, NULL, :createdAt
                        )
                        """)
                .param("id", run.id())
                .param("scenarioName", run.scenarioName())
                .param("scenarioVersion", run.scenarioVersion())
                .param("scenarioHash", run.scenarioHash())
                .param("targetProfileName", run.targetProfileName())
                .param("seed", run.seed())
                .param("status", run.status().name())
                .param("currentStepOrdinal", run.currentStepOrdinal())
                .param("compiledPlan", jsonb.ofJson(run.compiledPlan().toString()))
                .param("nextActionAt", Timestamp.from(run.nextActionAt()))
                .param("deadlineAt", Timestamp.from(run.deadlineAt()))
                .param("startedAt", run.startedAt() == null ? null : Timestamp.from(run.startedAt()))
                .param("createdAt", Timestamp.from(run.createdAt()))
                .update();
    }

    /**
     * 식별자로 Run을 조회한다.
     *
     * @param runId Run 식별자
     * @return 존재하면 Run
     */
    public Optional<ScenarioRun> findById(String runId) {
        return jdbcClient.sql("""
                        SELECT id, scenario_name, scenario_version, scenario_hash, target_profile_name,
                               seed, status, current_step_ordinal, compiled_plan::text, next_action_at,
                               deadline_at, lease_owner, lease_until, version, next_evidence_sequence,
                               cancel_requested, problem_code, problem_detail::text, started_at,
                               finished_at, created_at
                        FROM scenario_run
                        WHERE id = :runId
                        """)
                .param("runId", runId)
                .query(new RunRowMapper())
                .optional();
    }

    /**
     * 최근 Run을 생성 시각 역순으로 조회한다.
     *
     * @param limit 최대 조회 건수
     * @return Run 목록
     */
    public java.util.List<ScenarioRun> findRecent(int limit) {
        return jdbcClient.sql("""
                        SELECT id, scenario_name, scenario_version, scenario_hash, target_profile_name,
                               seed, status, current_step_ordinal, compiled_plan::text, next_action_at,
                               deadline_at, lease_owner, lease_until, version, next_evidence_sequence,
                               cancel_requested, problem_code, problem_detail::text, started_at,
                               finished_at, created_at
                        FROM scenario_run
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(new RunRowMapper())
                .list();
    }

    /**
     * 터미널이 아닌 활성 Run 수를 센다.
     *
     * <p>DoS 방어를 위한 동시 실행 Run 수 제한에 사용한다.</p>
     *
     * @return 활성 Run 수
     */
    public long countActive() {
        return jdbcClient.sql("""
                        SELECT count(*) FROM scenario_run
                        WHERE status NOT IN ('PASSED', 'FAILED', 'ERROR', 'CANCELLED')
                        """)
                .query(Long.class)
                .single();
    }

    /**
     * 지정 시각 이후 특정 상태의 Run 수를 센다.
     *
     * @param status Run 상태
     * @param since 기준 시각
     * @return 해당 상태의 Run 수
     */
    public long countByStatusSince(String status, java.time.Instant since) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM scenario_run
                        WHERE status = :status AND created_at >= :since
                        """)
                .param("status", status)
                .param("since", java.sql.Timestamp.from(since))
                .query(Long.class)
                .single();
    }

    /**
     * 지정 시각 이후 진행 중인데 정체된 Run 수를 센다.
     *
     * @param since next_action_at 기준 시각
     * @return 정체 Run 수
     */
    public long countStaleRuns(java.time.Instant since) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM scenario_run
                        WHERE status IN ('RUNNING', 'ASSERTING')
                          AND next_action_at < :since
                        """)
                .param("since", java.sql.Timestamp.from(since))
                .query(Long.class)
                .single();
    }

    /**
     * 최근 가장 많이 실패한 Scenario 이름을 반환한다.
     *
     * @return 최다 실패 Scenario 이름, 없으면 empty
     */
    public Optional<String> mostFailedScenario() {
        return jdbcClient.sql("""
                        SELECT scenario_name FROM scenario_run
                        WHERE status = 'FAILED'
                        GROUP BY scenario_name
                        ORDER BY count(*) DESC
                        LIMIT 1
                        """)
                .query(String.class)
                .optional();
    }

    /**
     * 기한이 도래한 활성 Run 하나를 원자적으로 lease한다.
     *
     * <p>왜 FOR UPDATE SKIP LOCKED인가: 여러 Worker가 동시에 due Run을 조회해도
     * 잠긴 행은 건너뛰고 각자 다른 Run을 인수해야 처리량이 유지되고 중복 실행이
     * 사라진다. lease 갱신은 같은 트랜잭션에서 완료된다.</p>
     *
     * @param workerId lease 소유자 표시
     * @param now 현재 시각
     * @param leaseDurationMs lease 유지 시간(밀리초)
     * @return lease에 성공하면 Run, due Run이 없으면 empty
     */
    @Transactional
    public Optional<ScenarioRun> leaseNextDueRun(String workerId, Instant now, long leaseDurationMs) {
        Optional<String> dueId = jdbcClient.sql("""
                        SELECT id
                        FROM scenario_run
                        WHERE status IN ('QUEUED', 'PREPARING', 'RUNNING', 'ASSERTING')
                          AND next_action_at <= :now
                          AND (lease_until IS NULL OR lease_until < :now)
                        ORDER BY next_action_at, created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("now", Timestamp.from(now))
                .query(String.class)
                .optional();
        if (dueId.isEmpty()) {
            return Optional.empty();
        }
        jdbcClient.sql("""
                        UPDATE scenario_run
                        SET lease_owner = :workerId,
                            lease_until = :leaseUntil
                        WHERE id = :runId
                        """)
                .param("workerId", workerId)
                .param("leaseUntil", Timestamp.from(now.plusMillis(leaseDurationMs)))
                .param("runId", dueId.get())
                .update();
        return findById(dueId.get());
    }

    /**
     * Run의 가변 필드를 optimistic version으로 갱신한다.
     *
     * <p>version이 일치하지 않으면 다른 Worker가 이미 이 Run을 인수한 것이다.
     * 갱신 실패는 false로 반환돼 호출자가 lease 상실을 인식한다.</p>
     *
     * @param run 갱신할 Run, version은 읽은 시점 값이어야 한다
     * @return 갱신 성공 여부
     */
    public boolean update(ScenarioRun run) {
        int updated = jdbcClient.sql("""
                        UPDATE scenario_run
                        SET status = :status,
                            current_step_ordinal = :currentStepOrdinal,
                            next_action_at = :nextActionAt,
                            lease_owner = :leaseOwner,
                            lease_until = :leaseUntil,
                            version = version + 1,
                            cancel_requested = :cancelRequested,
                            problem_code = :problemCode,
                            problem_detail = :problemDetail,
                            started_at = :startedAt,
                            finished_at = :finishedAt
                        WHERE id = :runId AND version = :expectedVersion
                        """)
                .param("status", run.status().name())
                .param("currentStepOrdinal", run.currentStepOrdinal())
                .param("nextActionAt", run.nextActionAt() == null ? null : Timestamp.from(run.nextActionAt()))
                .param("leaseOwner", run.leaseOwner())
                .param("leaseUntil", run.leaseUntil() == null ? null : Timestamp.from(run.leaseUntil()))
                .param("cancelRequested", run.cancelRequested())
                .param("problemCode", run.problemCode())
                .param("problemDetail", run.problemDetail() == null ? null : jsonb.ofJson(run.problemDetail().toString()))
                .param("startedAt", run.startedAt() == null ? null : Timestamp.from(run.startedAt()))
                .param("finishedAt", run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()))
                .param("runId", run.id())
                .param("expectedVersion", run.version())
                .update();
        return updated > 0;
    }

    /**
     * scenario_run 행을 도메인 record로 변환한다.
     *
     * <p>책임: JSONB 계획과 SQL 타입을 도메인 경계 안으로 옮긴다.</p>
     */
    private final class RunRowMapper implements RowMapper<ScenarioRun> {

        /**
         * 한 행을 ScenarioRun으로 변환한다.
         *
         * @param rs 결과셋
         * @param rowNum 행 번호
         * @return 변환된 Run
         * @throws SQLException 컬럼 접근 실패 시 발생한다
         */
        @Override
        public ScenarioRun mapRow(ResultSet rs, int rowNum) throws SQLException {
            Integer currentStep = (Integer) rs.getObject("current_step_ordinal");
            Timestamp leaseUntil = rs.getTimestamp("lease_until");
            Timestamp startedAt = rs.getTimestamp("started_at");
            Timestamp finishedAt = rs.getTimestamp("finished_at");
            JsonNode compiledPlan = jsonb.toTree(rs.getString("compiled_plan"));
            String problemDetailJson = rs.getString("problem_detail");
            return new ScenarioRun(
                    rs.getString("id"),
                    rs.getString("scenario_name"),
                    rs.getString("scenario_version"),
                    rs.getString("scenario_hash"),
                    rs.getString("target_profile_name"),
                    rs.getLong("seed"),
                    RunStatus.valueOf(rs.getString("status")),
                    currentStep,
                    compiledPlan,
                    toInstant(rs.getTimestamp("next_action_at")),
                    toInstant(rs.getTimestamp("deadline_at")),
                    rs.getString("lease_owner"),
                    leaseUntil == null ? null : leaseUntil.toInstant(),
                    rs.getLong("version"),
                    rs.getLong("next_evidence_sequence"),
                    rs.getBoolean("cancel_requested"),
                    rs.getString("problem_code"),
                    problemDetailJson == null ? null : jsonb.toTree(problemDetailJson),
                    startedAt == null ? null : startedAt.toInstant(),
                    finishedAt == null ? null : finishedAt.toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        }

        /**
         * SQL Timestamp를 Instant로 변환한다. null은 null로 유지한다.
         */
        private Instant toInstant(Timestamp timestamp) {
            return timestamp == null ? null : timestamp.toInstant();
        }
    }
}
