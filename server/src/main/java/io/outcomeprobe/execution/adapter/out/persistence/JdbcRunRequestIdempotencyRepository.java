package io.outcomeprobe.execution.adapter.out.persistence;

import io.outcomeprobe.execution.RunRequestIdempotencyPort;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Run 생성·재실행 idempotency ledger를 PostgreSQL 행 잠금으로 관리한다.
 *
 * <p>책임: 같은 operation과 key hash를 원자적으로 선점하고 권위 있는 request hash와
 * run 식별자를 반환한다. 원문 idempotency key와 요청 본문은 저장하지 않는다.</p>
 * <p>핵심 불변식: 충돌한 insert 뒤의 {@code FOR UPDATE} 조회는 최초 트랜잭션이 끝날 때까지
 * 대기하므로 동시 요청도 하나의 run 식별자로 수렴한다.</p>
 */
@Repository
public class JdbcRunRequestIdempotencyRepository implements RunRequestIdempotencyPort {

    private final JdbcClient jdbcClient;

    /**
     * SQL 실행기를 결속한다.
     *
     * @param jdbcClient PostgreSQL 접근 client
     */
    public JdbcRunRequestIdempotencyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * operation과 key hash 행을 최초 한 번만 만든다.
     *
     * @param operation CREATE 또는 RERUN
     * @param keyHash 원문 key의 SHA-256
     * @param requestHash canonical 요청의 SHA-256
     * @param createdAt 최초 접수 시각
     */
    @Override
    public void reserve(String operation, String keyHash, String requestHash, Instant createdAt) {
        jdbcClient.sql("""
                        INSERT INTO run_request_idempotency (
                            operation, key_hash, request_hash, run_id, created_at
                        ) VALUES (
                            :operation, :keyHash, :requestHash, NULL, :createdAt
                        )
                        ON CONFLICT (operation, key_hash) DO NOTHING
                        """)
                .param("operation", operation)
                .param("keyHash", keyHash)
                .param("requestHash", requestHash)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    /**
     * 권위 있는 ledger 행을 잠그고 읽는다.
     *
     * @param operation CREATE 또는 RERUN
     * @param keyHash 원문 key의 SHA-256
     * @return request hash와 이미 결속된 run 식별자
     */
    @Override
    public Entry lock(String operation, String keyHash) {
        return jdbcClient.sql("""
                        SELECT request_hash, run_id
                        FROM run_request_idempotency
                        WHERE operation = :operation AND key_hash = :keyHash
                        FOR UPDATE
                        """)
                .param("operation", operation)
                .param("keyHash", keyHash)
                .query((rs, rowNum) -> new Entry(
                        rs.getString("request_hash"), rs.getString("run_id")))
                .single();
    }

    /**
     * 선점한 ledger 행을 생성된 run에 결속한다.
     *
     * @param operation CREATE 또는 RERUN
     * @param keyHash 원문 key의 SHA-256
     * @param runId 생성된 run 식별자
     */
    @Override
    public void bindRun(String operation, String keyHash, String runId) {
        int updated = jdbcClient.sql("""
                        UPDATE run_request_idempotency
                        SET run_id = :runId
                        WHERE operation = :operation
                          AND key_hash = :keyHash
                          AND run_id IS NULL
                        """)
                .param("runId", runId)
                .param("operation", operation)
                .param("keyHash", keyHash)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Run idempotency ledger 결속에 실패했습니다.");
        }
    }

}
