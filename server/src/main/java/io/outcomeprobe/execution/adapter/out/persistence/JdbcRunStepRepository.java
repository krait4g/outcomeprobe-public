package io.outcomeprobe.execution.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.RunStepPort;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * run_step_execution 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: Step 생성·조회·상태 갱신을 제공해 엔진이 WAITING Step을
 * nextActionAt 기반으로 재개하게 한다.</p>
 * <p>핵심 불변식: 갱신은 optimistic version으로 보호돼, lease를 잃은 Worker의
 * 지연된 쓰기가 현재 소유자의 상태를 덮어쓰지 못한다.</p>
 */
@Repository
public class JdbcRunStepRepository implements RunStepPort {

    private final JdbcClient jdbcClient;
    private final JsonbSupport jsonb;

    /**
     * Step 저장에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param objectMapper JSONB 변환용 mapper
     */
    public JdbcRunStepRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * Step 행을 하나 저장한다.
     *
     * @param step 저장할 Step 실행
     */
    public void save(RunStepExecution step) {
        jdbcClient.sql("""
                        INSERT INTO run_step_execution (
                            id, run_id, step_id, ordinal, step_type, status, input_snapshot,
                            output_snapshot, wait_started_at, next_action_at, deadline_at,
                            attempt_count, last_observation, problem_code, version, started_at, finished_at
                        ) VALUES (
                            :id, :runId, :stepId, :ordinal, :stepType, :status, :inputSnapshot,
                            NULL, NULL, NULL, :deadlineAt,
                            0, NULL, NULL, 0, NULL, NULL
                        )
                        """)
                .param("id", step.id())
                .param("runId", step.runId())
                .param("stepId", step.stepId())
                .param("ordinal", step.ordinal())
                .param("stepType", step.stepType().name())
                .param("status", step.status().name())
                .param("inputSnapshot", jsonb.ofJson(step.inputSnapshot().toString()))
                .param("deadlineAt", step.deadlineAt() == null ? null : Timestamp.from(step.deadlineAt()))
                .update();
    }

    /**
     * Run의 Step 전부를 ordinal 순으로 조회한다.
     *
     * @param runId Run 식별자
     * @return ordinal 정렬된 Step 목록
     */
    public List<RunStepExecution> findByRunId(String runId) {
        return jdbcClient.sql("""
                        SELECT id, run_id, step_id, ordinal, step_type, status, input_snapshot::text,
                               output_snapshot::text, wait_started_at, next_action_at, deadline_at,
                               attempt_count, last_observation::text, problem_code, version,
                               started_at, finished_at
                        FROM run_step_execution
                        WHERE run_id = :runId
                        ORDER BY ordinal
                        """)
                .param("runId", runId)
                .query(new StepRowMapper())
                .list();
    }

    /**
     * Step 상태를 optimistic version으로 갱신한다.
     *
     * @param step 갱신할 Step, version은 읽은 시점 값이어야 한다
     * @return 갱신 성공 여부
     */
    public boolean update(RunStepExecution step) {
        int updated = jdbcClient.sql("""
                        UPDATE run_step_execution
                        SET status = :status,
                            output_snapshot = :outputSnapshot,
                            wait_started_at = :waitStartedAt,
                            next_action_at = :nextActionAt,
                            attempt_count = :attemptCount,
                            last_observation = :lastObservation,
                            problem_code = :problemCode,
                            version = version + 1,
                            started_at = :startedAt,
                            finished_at = :finishedAt
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("status", step.status().name())
                .param("outputSnapshot", step.outputSnapshot() == null
                        ? null : jsonb.ofJson(step.outputSnapshot().toString()))
                .param("waitStartedAt", step.waitStartedAt() == null
                        ? null : Timestamp.from(step.waitStartedAt()))
                .param("nextActionAt", step.nextActionAt() == null
                        ? null : Timestamp.from(step.nextActionAt()))
                .param("attemptCount", step.attemptCount())
                .param("lastObservation", step.lastObservation() == null
                        ? null : jsonb.ofJson(step.lastObservation().toString()))
                .param("problemCode", step.problemCode())
                .param("startedAt", step.startedAt() == null ? null : Timestamp.from(step.startedAt()))
                .param("finishedAt", step.finishedAt() == null ? null : Timestamp.from(step.finishedAt()))
                .param("id", step.id())
                .param("expectedVersion", step.version())
                .update();
        return updated > 0;
    }

    /**
     * run_step_execution 행을 도메인 record로 변환한다.
     *
     * <p>책임: JSONB snapshot과 SQL 시각 타입을 도메인 경계로 옮긴다.</p>
     */
    private final class StepRowMapper implements RowMapper<RunStepExecution> {

        /**
         * 한 행을 RunStepExecution으로 변환한다.
         *
         * @param rs 결과셋
         * @param rowNum 행 번호
         * @return 변환된 Step 실행
         * @throws SQLException 컬럼 접근 실패 시 발생한다
         */
        @Override
        public RunStepExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp waitStarted = rs.getTimestamp("wait_started_at");
            Timestamp nextAction = rs.getTimestamp("next_action_at");
            Timestamp deadline = rs.getTimestamp("deadline_at");
            Timestamp started = rs.getTimestamp("started_at");
            Timestamp finished = rs.getTimestamp("finished_at");
            String output = rs.getString("output_snapshot");
            String observation = rs.getString("last_observation");
            return new RunStepExecution(
                    rs.getString("id"),
                    rs.getString("run_id"),
                    rs.getString("step_id"),
                    rs.getInt("ordinal"),
                    StepType.valueOf(rs.getString("step_type")),
                    StepStatus.valueOf(rs.getString("status")),
                    jsonb.toTree(rs.getString("input_snapshot")),
                    output == null ? null : jsonb.toTree(output),
                    waitStarted == null ? null : waitStarted.toInstant(),
                    nextAction == null ? null : nextAction.toInstant(),
                    deadline == null ? null : deadline.toInstant(),
                    rs.getInt("attempt_count"),
                    observation == null ? null : jsonb.toTree(observation),
                    rs.getString("problem_code"),
                    rs.getLong("version"),
                    started == null ? null : started.toInstant(),
                    finished == null ? null : finished.toInstant());
        }
    }
}
