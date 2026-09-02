package io.outcomeprobe.emulator.adapter.out.persistence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.emulator.InvocationReceiptPort;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * provider_invocation_receipt 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: Provider idempotency 재사용 판단에 필요한 최초 결과와 요청 해시를
 * 영속화한다.</p>
 * <p>핵심 불변식: 영수증은 operation commit 트랜잭션 안에서 생성돼, commit 없이
 * 영수증만 존재하거나 그 반대인 상태를 만들지 않는다.</p>
 */
@Repository
public class JdbcInvocationReceiptRepository implements InvocationReceiptPort {

    private final JdbcClient jdbcClient;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final JsonbSupport jsonb;
    private final ObjectMapper objectMapper;

    /**
     * 영수증 관리에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param idGenerator 행 식별자 생성기
     * @param clockProvider 최초 commit 시각 제공자
     * @param objectMapper JSONB 변환용 mapper
     */
    public JdbcInvocationReceiptRepository(JdbcClient jdbcClient, IdGenerator idGenerator,
                                           ClockProvider clockProvider, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.objectMapper = objectMapper;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * 최초 응답 계획 snapshot을 조회한다.
     *
     * @param runId Run 식별자
     * @param operationKey operation key
     * @param idempotencyKeyHash 멱등 키 해시
     * @return 존재하면 최초 결과 snapshot
     */
    @Override
    public Optional<JsonNode> findResult(String runId, String operationKey, String idempotencyKeyHash) {
        return jdbcClient.sql("""
                        SELECT result_snapshot::text
                        FROM provider_invocation_receipt
                        WHERE run_id = :runId AND operation_key = :operationKey
                          AND idempotency_key_hash = :idempotencyKeyHash
                        """)
                .param("runId", runId)
                .param("operationKey", operationKey)
                .param("idempotencyKeyHash", idempotencyKeyHash)
                .query((java.sql.ResultSet rs, int rowNum) -> rs.getString(1))
                .optional()
                .map(json -> jsonb.toTree(json));
    }

    /**
     * 최초 commit 결과를 영수증으로 저장한다.
     *
     * @param runId Run 식별자
     * @param operationKey operation key
     * @param idempotencyKeyHash 멱등 키 해시
     * @param requestHash 요청 본문 해시
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @param resultSnapshot 최초 응답 계획 snapshot
     */
    @Override
    public void save(String runId, String operationKey, String idempotencyKeyHash, String requestHash,
                     String resourceType, String resourceId, JsonNode resultSnapshot) {
        jdbcClient.sql("""
                        INSERT INTO provider_invocation_receipt (
                            id, run_id, operation_key, idempotency_key_hash, request_hash,
                            resource_type, resource_id, result_snapshot, first_committed_at
                        ) VALUES (
                            :id, :runId, :operationKey, :idempotencyKeyHash, :requestHash,
                            :resourceType, :resourceId, :resultSnapshot, :firstCommittedAt
                        )
                        """)
                .param("id", idGenerator.nextId())
                .param("runId", runId)
                .param("operationKey", operationKey)
                .param("idempotencyKeyHash", idempotencyKeyHash)
                .param("requestHash", requestHash)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("resultSnapshot", jsonb.ofJson(resultSnapshot.toString()))
                .param("firstCommittedAt", Timestamp.from(clockProvider.now()))
                .update();
    }

    /**
     * 동일 key에 저장된 요청 해시를 조회한다.
     *
     * @param runId Run 식별자
     * @param operationKey operation key
     * @param idempotencyKeyHash 멱등 키 해시
     * @return 존재하면 요청 해시
     */
    @Override
    public Optional<String> findRequestHash(String runId, String operationKey, String idempotencyKeyHash) {
        return jdbcClient.sql("""
                        SELECT request_hash
                        FROM provider_invocation_receipt
                        WHERE run_id = :runId AND operation_key = :operationKey
                          AND idempotency_key_hash = :idempotencyKeyHash
                        """)
                .param("runId", runId)
                .param("operationKey", operationKey)
                .param("idempotencyKeyHash", idempotencyKeyHash)
                .query(String.class)
                .optional();
    }
}
