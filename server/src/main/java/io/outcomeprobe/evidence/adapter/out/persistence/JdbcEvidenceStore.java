package io.outcomeprobe.evidence.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.evidence.EvidenceMatcher;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.foundation.PostgresqlTimestamp;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evidence를 PostgreSQL에 append-only로 저장·조회하는 adapter다.
 *
 * <p>책임: sequence를 Run 행의 조건부 UPDATE·RETURNING으로 원자 할당하고,
 * 상태 변경과 Evidence가 같은 트랜잭션에 들어가도록 호출자 트랜잭션에 참여한다.</p>
 * <p>핵심 불변식: 이 클래스는 UPDATE·DELETE 문을 evidence_event에 발행하지 않는다.
 * DB trigger가 append-only를 추가로 보호한다.</p>
 */
@Repository
public class JdbcEvidenceStore implements EvidenceRecorder, EvidenceQueryPort {

    private final JdbcClient jdbcClient;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final JsonbSupport jsonb;

    /**
     * Evidence 저장에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param idGenerator Evidence 식별자 생성기
     * @param clockProvider 관측 시각 제공자
     * @param objectMapper JSONB 변환용 mapper
     */
    public JdbcEvidenceStore(JdbcClient jdbcClient, IdGenerator idGenerator,
                             ClockProvider clockProvider, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * 호출자 트랜잭션 안에서 sequence를 할당받고 Evidence를 한 건 insert한다.
     *
     * <p>왜 트랜잭션에 참여만 하는가: Provider commit·Step 상태 변경과 Evidence가
     * 원자적으로 보여야 Timeline이 실제 상태를 거짓 없이 나타낸다.</p>
     *
     * @param command 기록할 사건 명령
     * @return sequence와 관측 시각이 채워진 Evidence
     */
    @Override
    @Transactional
    public EvidenceEvent append(AppendEvidenceCommand command) {
        // Run 행을 조건부 UPDATE·RETURNING으로 증가시켜 동시 append에서도 sequence 중복을 막는다.
        Long sequence = jdbcClient.sql("""
                        UPDATE scenario_run
                        SET next_evidence_sequence = next_evidence_sequence + 1
                        WHERE id = :runId
                        RETURNING next_evidence_sequence
                        """)
                .param("runId", command.runId())
                .query(Long.class)
                .single();

        Instant createdAt = PostgresqlTimestamp.normalize(clockProvider.now());
        Instant observedAt = command.observedAt() == null
                ? createdAt
                : PostgresqlTimestamp.normalize(command.observedAt());
        String id = idGenerator.nextId();

        jdbcClient.sql("""
                        INSERT INTO evidence_event (
                            id, run_id, sequence, logical_time_ms, observed_at, type, source,
                            step_id, correlation_id, resource_type, resource_id,
                            attributes, payload_summary, body_hash, redaction_applied, created_at
                        ) VALUES (
                            :id, :runId, :sequence, :logicalTimeMs, :observedAt, :type, :source,
                            :stepId, :correlationId, :resourceType, :resourceId,
                            :attributes, CAST(:payloadSummary AS jsonb), :bodyHash, :redactionApplied, :createdAt
                        )
                        """)
                .param("id", id)
                .param("runId", command.runId())
                .param("sequence", sequence)
                .param("logicalTimeMs", command.logicalTimeMs())
                .param("observedAt", Timestamp.from(observedAt))
                .param("type", command.type().name())
                .param("source", command.source().name())
                .param("stepId", command.stepId())
                .param("correlationId", command.correlationId())
                .param("resourceType", command.resourceType())
                .param("resourceId", command.resourceId())
                .param("attributes", jsonb.ofAttributes(command.attributes()))
                .param("payloadSummary", command.payloadSummary())
                .param("bodyHash", command.bodyHash())
                .param("redactionApplied", command.redactionApplied())
                .param("createdAt", Timestamp.from(createdAt))
                .update();

        return new EvidenceEvent(id, command.runId(), sequence, command.logicalTimeMs(),
                observedAt, command.type(),
                command.source(), command.stepId(), command.correlationId(),
                command.resourceType(), command.resourceId(), command.attributes(),
                command.payloadSummary(), command.bodyHash(), command.redactionApplied(), createdAt);
    }

    /**
     * 조건과 일치하는 Evidence 건수를 센다.
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @return 조건을 만족하는 Evidence 건수
     */
    @Override
    public long count(String runId, EvidenceMatcher matcher) {
        return jdbcClient.sql(matcherSql("count(*)", matcher))
                .param("runId", runId)
                .param("type", matcher.type().name())
                .param("attributes", jsonb.ofAttributes(matcher.attributes()))
                .query(Long.class)
                .single();
    }

    /**
     * 조건과 일치하는 첫 사건의 sequence를 반환한다.
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @return 첫 일치 Evidence의 sequence, 없으면 empty
     */
    @Override
    public Optional<Long> firstSequenceOf(String runId, EvidenceMatcher matcher) {
        List<Long> sequences = jdbcClient.sql(matcherSql("min(sequence)", matcher))
                .param("runId", runId)
                .param("type", matcher.type().name())
                .param("attributes", jsonb.ofAttributes(matcher.attributes()))
                .query(Long.class)
                .list();
        return sequences.stream().findFirst().flatMap(Optional::ofNullable);
    }

    /**
     * 조건과 일치하는 Evidence 존재 여부를 반환한다.
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @return 존재 여부
     */
    @Override
    public boolean exists(String runId, EvidenceMatcher matcher) {
        return count(runId, matcher) > 0;
    }

    /**
     * 조건과 일치하면서 지정한 마감 시각까지 관측된 Evidence가 존재하는지 조회한다.
     *
     * <p>{@code created_at}은 저장 시각일 뿐 실제 관측 시각이 아니므로 시간 판정에는
     * {@code observed_at}만 사용한다. 마감 시각과 정확히 같은 관측은 허용한다.</p>
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @param observedAtInclusive 포함할 마지막 관측 시각
     * @return 시간 경계 안의 일치 Evidence 존재 여부
     */
    @Override
    public boolean existsObservedAtOrBefore(String runId, EvidenceMatcher matcher,
                                            Instant observedAtInclusive) {
        return jdbcClient.sql(matcherSql("count(*)", matcher)
                        + " AND observed_at <= :observedAtInclusive")
                .param("runId", runId)
                .param("type", matcher.type().name())
                .param("attributes", jsonb.ofAttributes(matcher.attributes()))
                .param("observedAtInclusive", Timestamp.from(
                        PostgresqlTimestamp.normalize(observedAtInclusive)))
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Run 전체 Timeline을 sequence 오름차순으로 반환한다.
     *
     * @param runId 조회할 Run 식별자
     * @return sequence 정렬된 Evidence 목록
     */
    @Override
    public List<EvidenceEvent> timeline(String runId) {
        return jdbcClient.sql("""
                        SELECT id, run_id, sequence, logical_time_ms, observed_at, type, source,
                               step_id, correlation_id, resource_type, resource_id,
                               attributes::text, payload_summary::text, body_hash, redaction_applied, created_at
                        FROM evidence_event
                        WHERE run_id = :runId
                        ORDER BY sequence
                        """)
                .param("runId", runId)
                .query(new EvidenceRowMapper())
                .list();
    }

    /**
     * matcher 조건에 맞는 조회 SQL을 조립한다. 속성은 JSONB containment로 전부 일치를 강제한다.
     */
    private String matcherSql(String selectExpression, EvidenceMatcher matcher) {
        // 속성 조건은 JSONB containment로 전부 일치(all-of)만 지원한다.
        String attributeClause = matcher.attributes().isEmpty()
                ? ""
                : " AND attributes @> :attributes";
        return "SELECT " + selectExpression + " FROM evidence_event"
                + " WHERE run_id = :runId AND type = :type" + attributeClause;
    }

    /**
     * evidence_event 행을 도메인 record로 변환하는 mapper다.
     *
     * <p>책임: SQL 타입과 도메인 타입의 경계를 한 곳에서 관리한다.</p>
     */
    private final class EvidenceRowMapper implements RowMapper<EvidenceEvent> {

        /**
         * 한 행을 EvidenceEvent로 변환한다.
         *
         * PREPARED STATEMENT 결과를 도메인 record로 매핑한다.
         *
         * @param rs 결과셋
         * @param rowNum 행 번호
         * @return 변환된 Evidence
         * @throws SQLException 컬럼 접근 실패 시 발생한다
         */
        @Override
        public EvidenceEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long logicalTime = rs.getObject("logical_time_ms") == null
                    ? null
                    : rs.getLong("logical_time_ms");
            return new EvidenceEvent(
                    rs.getString("id"),
                    rs.getString("run_id"),
                    rs.getLong("sequence"),
                    logicalTime,
                    rs.getTimestamp("observed_at").toInstant(),
                    EvidenceType.valueOf(rs.getString("type")),
                    EvidenceSource.valueOf(rs.getString("source")),
                    rs.getString("step_id"),
                    rs.getString("correlation_id"),
                    rs.getString("resource_type"),
                    rs.getString("resource_id"),
                    jsonb.toAttributes(rs.getString("attributes")),
                    rs.getString("payload_summary"),
                    rs.getString("body_hash"),
                    rs.getBoolean("redaction_applied"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
