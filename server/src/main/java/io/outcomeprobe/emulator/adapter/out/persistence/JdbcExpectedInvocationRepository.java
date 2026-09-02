package io.outcomeprobe.emulator.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.emulator.ExpectedInvocation;
import io.outcomeprobe.emulator.ExpectedInvocationPort;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * expected_invocation 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: expectation 등록·후보 조회·매칭 횟수 CAS 증가·미충족 판정을 제공한다.</p>
 * <p>핵심 불변식: matched count 증가는 version CAS로만 일어나며, 동일 operation의
 * 동시 요청 중 용량 초과 매칭을 차단한다.</p>
 */
@Repository
public class JdbcExpectedInvocationRepository implements ExpectedInvocationPort {

    private final JdbcClient jdbcClient;
    private final IdGenerator idGenerator;
    private final JsonbSupport jsonb;

    /**
     * expectation 관리에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param idGenerator 행 식별자 생성기
     * @param objectMapper JSONB 변환용 mapper
     */
    public JdbcExpectedInvocationRepository(JdbcClient jdbcClient, IdGenerator idGenerator,
                                            ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.idGenerator = idGenerator;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * expectation을 등록한다.
     *
     * @param expectation 등록할 expectation
     * @return 식별자가 채워진 expectation
     */
    @Override
    public ExpectedInvocation register(ExpectedInvocation expectation) {
        String id = expectation.id() == null ? idGenerator.nextId() : expectation.id();
        jdbcClient.sql("""
                        INSERT INTO expected_invocation (
                            id, run_id, step_id, operation_key, matcher, behavior,
                            minimum_count, maximum_count, matched_count, status, expires_at, version
                        ) VALUES (
                            :id, :runId, :stepId, :operationKey, :matcher, :behavior,
                            :minimumCount, :maximumCount, 0, 'REGISTERED', NULL, 0
                        )
                        """)
                .param("id", id)
                .param("runId", expectation.runId())
                .param("stepId", expectation.stepId())
                .param("operationKey", expectation.operationKey())
                .param("matcher", jsonb.ofJson(expectation.matcher().toString()))
                .param("behavior", jsonb.ofJson(expectation.behavior().toString()))
                .param("minimumCount", expectation.minimumCount())
                .param("maximumCount", expectation.maximumCount())
                .update();
        return new ExpectedInvocation(id, expectation.runId(), expectation.stepId(),
                expectation.operationKey(), expectation.ordinal(), expectation.matcher(),
                expectation.behavior(), expectation.minimumCount(), expectation.maximumCount(),
                0, 0);
    }

    /**
     * 용량이 남은 expectation을 등록 순서대로 조회한다.
     *
     * <p>왜 순서가 중요한가: 같은 operation을 여러 번 기대하는 Scenario에서
     * occurrence별 fault 계획이 순서대로 적용돼야 재현이 결정적이다.</p>
     *
     * @param runId Run 식별자
     * @param operationKey Contract operation key
     * @return 후보 expectation 목록
     */
    @Override
    public List<ExpectedInvocation> candidates(String runId, String operationKey) {
        return jdbcClient.sql("""
                        SELECT id, run_id, step_id, operation_key, matcher::text, behavior::text,
                               minimum_count, maximum_count, matched_count, version
                        FROM expected_invocation
                        WHERE run_id = :runId
                          AND operation_key = :operationKey
                          AND matched_count < maximum_count
                        ORDER BY id
                        """)
                .param("runId", runId)
                .param("operationKey", operationKey)
                .query(new ExpectationRowMapper())
                .list();
    }

    /**
     * matched count를 CAS로 증가시킨다.
     *
     * <p>version이 일치하고 아직 용량이 남았을 때만 증가한다. 이 조건이 깨지면
     * 동시 요청이 이미 이 expectation을 소모한 것이다.</p>
     *
     * @param id expectation 식별자
     * @param expectedVersion 읽은 시점의 version
     * @return 증가 성공 여부
     */
    @Override
    public boolean recordMatch(String id, long expectedVersion) {
        int updated = jdbcClient.sql("""
                        UPDATE expected_invocation
                        SET matched_count = matched_count + 1,
                            status = 'MATCHED',
                            version = version + 1
                        WHERE id = :id
                          AND version = :expectedVersion
                          AND matched_count < maximum_count
                        """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated > 0;
    }

    /**
     * 최소 횟수에 도달하지 못한 expectation을 조회한다.
     *
     * @param runId Run 식별자
     * @return 미충족 expectation 목록
     */
    @Override
    public List<ExpectedInvocation> unsatisfied(String runId) {
        return jdbcClient.sql("""
                        SELECT id, run_id, step_id, operation_key, matcher::text, behavior::text,
                               minimum_count, maximum_count, matched_count, version
                        FROM expected_invocation
                        WHERE run_id = :runId AND matched_count < minimum_count
                        ORDER BY id
                        """)
                .param("runId", runId)
                .query(new ExpectationRowMapper())
                .list();
    }

    /**
     * 특정 operation의 첫 expectation을 조회한다.
     *
     * @param runId Run 식별자
     * @param operationKey Contract operation key
     * @return 존재하면 첫 expectation
     */
    @Override
    public Optional<ExpectedInvocation> firstByOperation(String runId, String operationKey) {
        return candidates(runId, operationKey).stream().findFirst();
    }

    /**
     * expected_invocation 행을 도메인 record로 변환한다.
     *
     * <p>책임: JSONB matcher·behavior를 도메인이 소비하는 JsonNode로 경계 짓는다.</p>
     */
    private final class ExpectationRowMapper implements RowMapper<ExpectedInvocation> {

        /**
         * 한 행을 ExpectedInvocation으로 변환한다.
         *
         * @param rs 결과셋
         * @param rowNum 행 번호
         * @return 변환된 expectation
         * @throws SQLException 컬럼 접근 실패 시 발생한다
         */
        @Override
        public ExpectedInvocation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExpectedInvocation(
                    rs.getString("id"),
                    rs.getString("run_id"),
                    rs.getString("step_id"),
                    rs.getString("operation_key"),
                    0,
                    jsonb.toTree(rs.getString("matcher")),
                    jsonb.toTree(rs.getString("behavior")),
                    rs.getInt("minimum_count"),
                    rs.getInt("maximum_count"),
                    rs.getInt("matched_count"),
                    rs.getLong("version"));
        }
    }
}
