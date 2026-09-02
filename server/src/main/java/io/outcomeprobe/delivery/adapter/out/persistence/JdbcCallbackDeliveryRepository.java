package io.outcomeprobe.delivery.adapter.out.persistence;

import io.outcomeprobe.delivery.CallbackDelivery;
import io.outcomeprobe.delivery.CallbackDeliveryRepository;
import io.outcomeprobe.delivery.CallbackEvent;
import io.outcomeprobe.delivery.DeliveryStatus;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * callback_event·callback_delivery 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: 사건·occurrence 저장과 due occurrence의 원자 lease, 결과 기록을
 * 제공한다.</p>
 * <p>핵심 불변식: lease 획득은 SELECT FOR UPDATE SKIP LOCKED와 lease 갱신을
 * 같은 트랜잭션에서 완료한다. HTTP 전송은 이 트랜잭션 밖에서 일어난다.</p>
 */
@Repository
public class JdbcCallbackDeliveryRepository implements CallbackDeliveryRepository {

    private final JdbcClient jdbcClient;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final JsonbSupport jsonb;

    /**
     * 저장에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param idGenerator 식별자 생성기
     * @param clockProvider 시각 제공자
     * @param objectMapper JSONB 변환용 mapper
     */
    public JdbcCallbackDeliveryRepository(JdbcClient jdbcClient, IdGenerator idGenerator,
                                          ClockProvider clockProvider,
                                          tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * 사건을 insert한다.
     *
     * @param event 저장할 사건
     * @return 식별자가 채워진 사건
     */
    @Override
    public CallbackEvent saveEvent(CallbackEvent event) {
        String id = event.id() == null ? idGenerator.nextId() : event.id();
        CallbackEvent persisted = jdbcClient.sql("""
                        INSERT INTO callback_event (
                            id, run_id, callback_key, event_id, event_type,
                            resource_type, resource_id, resource_version, payload, payload_hash, created_at
                        ) VALUES (
                            :id, :runId, :callbackKey, :eventId, :eventType,
                            :resourceType, :resourceId, :resourceVersion,
                            CAST(:payload AS jsonb), :payloadHash, :createdAt
                        )
                        ON CONFLICT (run_id, event_id) DO UPDATE
                        SET event_id = EXCLUDED.event_id
                        RETURNING id, run_id, callback_key, event_id, event_type,
                                  resource_type, resource_id, resource_version,
                                  payload::text, payload_hash
                        """)
                .param("id", id)
                .param("runId", event.runId())
                .param("callbackKey", event.callbackKey())
                .param("eventId", event.eventId())
                .param("eventType", event.eventType())
                .param("resourceType", event.resourceType())
                .param("resourceId", event.resourceId())
                .param("resourceVersion", event.resourceVersion())
                .param("payload", jsonb.write(event.payload()))
                .param("payloadHash", event.payloadHash())
                .param("createdAt", Timestamp.from(clockProvider.now()))
                .query(this::mapEvent)
                .single();
        verifyEventIdentity(event, persisted);
        return persisted;
    }

    /**
     * 전달 occurrence를 insert한다.
     *
     * @param delivery 저장할 occurrence
     * @return 식별자가 채워진 occurrence
     */
    @Override
    public CallbackDelivery saveDelivery(CallbackDelivery delivery) {
        String id = delivery.id() == null ? idGenerator.nextId() : delivery.id();
        return jdbcClient.sql("""
                        INSERT INTO callback_delivery (
                            id, run_id, callback_event_id, occurrence, status, scheduled_at,
                            next_attempt_at, attempt_count, maximum_attempts, lease_owner,
                            lease_until, request_snapshot, response_snapshot, last_failure_code,
                            version, completed_at
                        ) VALUES (
                            :id, :runId, :eventId, :occurrence, :status, :scheduledAt,
                            :nextAttemptAt, 0, :maximumAttempts, NULL,
                            NULL, CAST(:requestSnapshot AS jsonb), NULL, NULL,
                            0, NULL
                        )
                        ON CONFLICT (callback_event_id, occurrence) DO UPDATE
                        SET occurrence = EXCLUDED.occurrence
                        RETURNING id, run_id, callback_event_id, occurrence, status, scheduled_at,
                                  next_attempt_at, attempt_count, maximum_attempts, lease_owner,
                                  lease_until, last_failure_code, request_snapshot::text,
                                  version, completed_at
                        """)
                .param("id", id)
                .param("runId", delivery.runId())
                .param("eventId", delivery.callbackEventId())
                .param("occurrence", delivery.occurrence())
                .param("status", delivery.status().name())
                .param("scheduledAt", Timestamp.from(delivery.scheduledAt()))
                .param("nextAttemptAt", Timestamp.from(delivery.nextAttemptAt()))
                .param("maximumAttempts", delivery.maximumAttempts())
                .param("requestSnapshot", jsonb.write(delivery.requestSnapshot()))
                .query(new DeliveryRowMapper())
                .single();
    }

    /**
     * 사건을 조회한다.
     *
     * @param eventId 사건 식별자
     * @return 존재하면 사건
     */
    @Override
    public Optional<CallbackEvent> findEvent(String eventId) {
        return jdbcClient.sql("""
                        SELECT id, run_id, callback_key, event_id, event_type,
                               resource_type, resource_id, resource_version, payload::text, payload_hash
                        FROM callback_event
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .query(this::mapEvent)
                .optional();
    }

    /**
     * Callback 쓰기보다 먼저 부모 Run 행 잠금을 획득한다.
     *
     * @param runId 부모 Run 식별자
     * @return Run이 존재해 잠금을 획득했으면 true
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockRunForDeliveryMutation(String runId) {
        return jdbcClient.sql("""
                        SELECT id
                        FROM scenario_run
                        WHERE id = :runId
                        FOR UPDATE
                        """)
                .param("runId", runId)
                .query(String.class)
                .optional()
                .isPresent();
    }

    /**
     * 복구 대상 부모 Run 하나를 식별자 순으로 먼저 잠근다.
     *
     * <p>READ COMMITTED에서 후보 조회 뒤 새 delivery가 생겨도 이후 UPDATE는 반환한 runId로만
     * 제한한다. tick당 부모 하나만 잠가 여러 Worker가 서로 다른 부모를 잡은 뒤 교차 대기하는
     * 상황도 피한다.</p>
     *
     * @param now lease 만료 판정 시각
     * @return 잠근 복구 대상 부모 Run 식별자
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> lockNextRunForExpiredRecovery(Instant now) {
        return jdbcClient.sql("""
                        SELECT r.id
                        FROM scenario_run r
                        WHERE EXISTS (
                            SELECT 1
                            FROM callback_delivery d
                            WHERE d.run_id = r.id
                              AND (
                                  (
                                      d.status IN ('SCHEDULED', 'RETRY_WAIT')
                                      AND (
                                          r.status IN ('CANCELLED', 'ERROR')
                                          OR (r.status = 'FAILED'
                                              AND r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED')
                                      )
                                  )
                                  OR (
                                      d.status IN ('LEASED', 'ATTEMPTING')
                                      AND d.lease_until < :now
                                      AND (
                                          d.attempt_count >= d.maximum_attempts
                                          OR r.status IN ('CANCELLED', 'ERROR')
                                          OR (r.status = 'FAILED'
                                              AND r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED')
                                      )
                                  )
                              )
                        )
                        ORDER BY r.id
                        LIMIT 1
                        FOR UPDATE OF r SKIP LOCKED
                        """)
                .param("now", Timestamp.from(now))
                .query(String.class)
                .optional();
    }

    /**
     * Event upsert가 기존 행을 반환했을 때 동일 Run의 같은 의미적 사건인지 검증한다.
     *
     * <p>payload는 clock template 때문에 재개 시 다시 렌더링한 후보와 달라질 수 있으므로
     * 최초 저장 행을 권위 있는 snapshot으로 유지한다. 이후 Delivery는 반환된 payload hash를
     * 사용해 그 snapshot에 결속된다.</p>
     *
     * @param requested 이번 materialization이 제안한 사건
     * @param persisted DB가 새로 저장했거나 기존 identity에서 반환한 사건
     */
    private void verifyEventIdentity(CallbackEvent requested, CallbackEvent persisted) {
        if (!Objects.equals(requested.runId(), persisted.runId())
                || !Objects.equals(requested.callbackKey(), persisted.callbackKey())
                || !Objects.equals(requested.eventId(), persisted.eventId())
                || !Objects.equals(requested.eventType(), persisted.eventType())
                || !Objects.equals(requested.resourceType(), persisted.resourceType())
                || !Objects.equals(requested.resourceId(), persisted.resourceId())
                || !Objects.equals(requested.resourceVersion(), persisted.resourceVersion())) {
            throw new IllegalStateException(
                    "기존 Callback event가 현재 Run 계획과 일치하지 않습니다: " + requested.eventId());
        }
    }

    /**
     * callback_event 결과 행을 도메인 사건으로 변환한다.
     *
     * @param rs 결과셋
     * @param rowNum 행 번호
     * @return 변환된 Callback 사건
     * @throws SQLException 컬럼 접근 실패 시 발생한다
     */
    private CallbackEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new CallbackEvent(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("callback_key"),
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getObject("resource_version") == null ? null : rs.getLong("resource_version"),
                jsonb.toTree(rs.getString("payload")),
                rs.getString("payload_hash"));
    }

    /**
     * due occurrence 하나를 원자 lease한다.
     *
     * <p>왜 Run 상태를 조인하는가: 취소·오류로 끝난 Run의 전달을 계속 시도하면
     * Target에 불필요한 부작용이 생긴다.</p>
     *
     * @param workerId lease 소유자
     * @param now 현재 시각
     * @param leaseDurationMs lease 유지 시간
     * @return lease 성공 시 occurrence
     */
    @Override
    @Transactional
    public Optional<CallbackDelivery> leaseNextDue(String workerId, Instant now, long leaseDurationMs) {
        Optional<String> dueId = jdbcClient.sql("""
                        WITH locked_run AS MATERIALIZED (
                            SELECT r.id
                            FROM scenario_run r
                            JOIN LATERAL (
                                SELECT MIN(candidate.next_attempt_at) AS earliest_due
                                FROM callback_delivery candidate
                                WHERE candidate.run_id = r.id
                                  AND candidate.attempt_count < candidate.maximum_attempts
                                  AND (
                                      (candidate.status IN ('SCHEDULED', 'RETRY_WAIT')
                                       AND candidate.next_attempt_at <= :now
                                       AND (candidate.lease_until IS NULL
                                            OR candidate.lease_until < :now))
                                      OR (candidate.status IN ('LEASED', 'ATTEMPTING')
                                          AND candidate.lease_until < :now)
                                  )
                            ) due ON due.earliest_due IS NOT NULL
                            WHERE r.status NOT IN ('CANCELLED', 'ERROR')
                              AND NOT (r.status = 'FAILED'
                                       AND r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED')
                            ORDER BY due.earliest_due, r.id
                            LIMIT 1
                            FOR UPDATE OF r SKIP LOCKED
                        )
                        SELECT d.id
                        FROM callback_delivery d
                        JOIN locked_run r ON r.id = d.run_id
                        WHERE d.attempt_count < d.maximum_attempts
                          AND (
                            (d.status IN ('SCHEDULED', 'RETRY_WAIT')
                             AND d.next_attempt_at <= :now
                             AND (d.lease_until IS NULL OR d.lease_until < :now))
                            OR
                            (d.status IN ('LEASED', 'ATTEMPTING') AND d.lease_until < :now)
                        )
                        ORDER BY d.next_attempt_at, d.id
                        LIMIT 1
                        FOR UPDATE OF d SKIP LOCKED
                        """)
                .param("now", Timestamp.from(now))
                .query(String.class)
                .optional();
        if (dueId.isEmpty()) {
            return Optional.empty();
        }
        jdbcClient.sql("""
                        UPDATE callback_delivery
                        SET status = 'LEASED',
                            lease_owner = :workerId,
                            lease_until = :leaseUntil,
                            version = version + 1
                        WHERE id = :id
                        """)
                .param("workerId", workerId)
                .param("leaseUntil", Timestamp.from(now.plusMillis(leaseDurationMs)))
                .param("id", dueId.get())
                .update();
        return findById(dueId.get());
    }

    /**
     * lease snapshot이 아직 권위 있을 때만 ATTEMPTING으로 CAS 전이한다.
     *
     * <p>terminal recovery나 다른 Worker가 version을 바꿨으면 0건을 반환해 호출자가 HTTP를
     * 보내지 못하게 한다. 성공 시 짧아진 lease를 실제 전송 구간 전체 길이로 갱신한다.</p>
     *
     * @param leased lease 시점 occurrence snapshot
     * @param now 권한·만료 확인 시각
     * @param leaseDurationMs 실제 전송 구간에 갱신할 lease 기간
     * @return 권한이 유지되면 갱신된 snapshot
     */
    @Override
    public Optional<CallbackDelivery> beginAttempt(CallbackDelivery leased, Instant now,
                                                   long leaseDurationMs) {
        return jdbcClient.sql("""
                        UPDATE callback_delivery d
                        SET status = 'ATTEMPTING',
                            attempt_count = d.attempt_count + 1,
                            lease_until = :leaseUntil,
                            version = d.version + 1
                        FROM scenario_run r
                        WHERE d.id = :id
                          AND r.id = d.run_id
                          AND d.status = 'LEASED'
                          AND d.lease_owner = :leaseOwner
                          AND d.lease_until >= :now
                          AND d.version = :expectedVersion
                          AND d.attempt_count < d.maximum_attempts
                          AND r.status NOT IN ('CANCELLED', 'ERROR')
                          AND NOT (r.status = 'FAILED'
                                   AND r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED')
                        RETURNING d.id, d.run_id, d.callback_event_id, d.occurrence, d.status,
                                  d.scheduled_at, d.next_attempt_at, d.attempt_count,
                                  d.maximum_attempts, d.lease_owner, d.lease_until,
                                  d.last_failure_code,
                                  d.request_snapshot::text AS request_snapshot,
                                  d.version, d.completed_at
                        """)
                .param("leaseUntil", Timestamp.from(now.plusMillis(leaseDurationMs)))
                .param("id", leased.id())
                .param("leaseOwner", leased.leaseOwner())
                .param("now", Timestamp.from(now))
                .param("expectedVersion", leased.version())
                .query(new DeliveryRowMapper())
                .optional();
    }

    /**
     * Run terminal transaction에서 아직 시작하지 않은 전달을 일괄 취소한다.
     *
     * <p>LEASED는 아직 HTTP 전이므로 함께 취소해 beginAttempt와 같은 행 lock에서
     * 직렬화한다. ATTEMPTING만 외부 HTTP가 시작된 상태로 보존하고, Worker가 죽었다면 lease
     * 만료 뒤 {@link #markExpiredForTerminalRunUnknown(String, Instant)}가 결과 불명으로
     * 닫는다.</p>
     *
     * @param runId terminal 전환할 Run 식별자
     * @param completedAt 취소 확정 시각
     * @return 취소한 occurrence 수
     */
    @Override
    public int cancelPendingForRun(String runId, Instant completedAt) {
        return jdbcClient.sql("""
                        UPDATE callback_delivery
                        SET status = 'CANCELLED',
                            lease_owner = NULL,
                            lease_until = NULL,
                            completed_at = :completedAt,
                            version = version + 1
                        WHERE run_id = :runId
                          AND status IN ('SCHEDULED', 'RETRY_WAIT', 'LEASED')
                        """)
                .param("completedAt", Timestamp.from(completedAt))
                .param("runId", runId)
                .update();
    }

    /**
     * 업그레이드 전에 terminal 부모 아래 남은 미시작 전달만 정확한 잠금 범위에서 취소한다.
     *
     * @param lockedRunId 같은 transaction에서 먼저 잠근 부모 Run 식별자
     * @param completedAt 취소 확정 시각
     * @return 취소한 occurrence 수
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelStrandedPendingForTerminalRun(String lockedRunId, Instant completedAt) {
        return jdbcClient.sql("""
                        UPDATE callback_delivery d
                        SET status = 'CANCELLED',
                            lease_owner = NULL,
                            lease_until = NULL,
                            completed_at = :completedAt,
                            version = d.version + 1
                        FROM scenario_run r
                        WHERE d.run_id = :runId
                          AND r.id = d.run_id
                          AND d.status IN ('SCHEDULED', 'RETRY_WAIT')
                          AND (
                              r.status IN ('CANCELLED', 'ERROR')
                              OR (r.status = 'FAILED'
                                  AND r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED')
                          )
                        """)
                .param("completedAt", Timestamp.from(completedAt))
                .param("runId", lockedRunId)
                .update();
    }

    /**
     * 취소·오류·전체 deadline Run에 고착된 만료 lease를 결과 불명으로 확정한다.
     *
     * <p>lease 획득만으로 HTTP 미전송을 증명할 수 없으므로 CANCELLED로 쓰지 않는다.
     * 일반 FAILED는 Callback 전달을 계속할 수 있어 timeout problem code일 때만 terminal
     * 복구 대상으로 제한한다.</p>
     *
     * @param lockedRunId 같은 transaction에서 먼저 잠근 부모 Run 식별자
     * @param now lease 만료 판정과 완료 시각
     * @return 결과 불명으로 전환한 occurrence snapshot
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CallbackDelivery> markExpiredForTerminalRunUnknown(
            String lockedRunId, Instant now) {
        return jdbcClient.sql("""
                        UPDATE callback_delivery d
                        SET status = 'UNKNOWN',
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_failure_code = 'DELIVERY_OUTCOME_UNKNOWN_AFTER_RUN_TERMINATION',
                            completed_at = :now,
                            version = d.version + 1
                        FROM scenario_run r
                        WHERE d.run_id = :runId
                          AND r.id = d.run_id
                          AND d.status IN ('LEASED', 'ATTEMPTING')
                          AND d.lease_until < :now
                          AND (
                              r.status IN ('CANCELLED', 'ERROR')
                              OR (r.status = 'FAILED'
                                  AND r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED')
                          )
                        RETURNING d.id, d.run_id, d.callback_event_id, d.occurrence, d.status,
                                  d.scheduled_at, d.next_attempt_at, d.attempt_count,
                                  d.maximum_attempts, d.lease_owner, d.lease_until,
                                  d.last_failure_code, d.request_snapshot::text AS request_snapshot,
                                  d.version, d.completed_at
                        """)
                .param("runId", lockedRunId)
                .param("now", Timestamp.from(now))
                .query(new DeliveryRowMapper())
                .list();
    }

    /**
     * 마지막 HTTP attempt 뒤 결과 commit 전에 중단된 occurrence를 UNKNOWN으로 확정한다.
     *
     * <p>attempt count는 외부 전송 직전에 이미 증가한다. 따라서 만료 시 상한을 모두
     * 소비했다면 같은 event를 무제한 재전송하지 않고 실제 결과의 모호성을 보존한다.</p>
     *
     * @param lockedRunId 같은 transaction에서 먼저 잠근 부모 Run 식별자
     * @param now lease 만료 판정과 완료 시각
     * @return 재시도 상한을 소진해 결과 불명으로 전환한 occurrence snapshot
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CallbackDelivery> markExpiredExhaustedAttemptsUnknown(
            String lockedRunId, Instant now) {
        return jdbcClient.sql("""
                        UPDATE callback_delivery d
                        SET status = 'UNKNOWN',
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_failure_code =
                                'DELIVERY_OUTCOME_UNKNOWN_AFTER_ATTEMPT_LIMIT',
                            completed_at = :now,
                            version = d.version + 1
                        WHERE d.run_id = :runId
                          AND d.status IN ('LEASED', 'ATTEMPTING')
                          AND d.lease_until < :now
                          AND d.attempt_count >= d.maximum_attempts
                        RETURNING d.id, d.run_id, d.callback_event_id, d.occurrence, d.status,
                                  d.scheduled_at, d.next_attempt_at, d.attempt_count,
                                  d.maximum_attempts, d.lease_owner, d.lease_until,
                                  d.last_failure_code, d.request_snapshot::text AS request_snapshot,
                                  d.version, d.completed_at
                        """)
                .param("runId", lockedRunId)
                .param("now", Timestamp.from(now))
                .query(new DeliveryRowMapper())
                .list();
    }

    /**
     * occurrence를 optimistic version으로 갱신한다.
     *
     * <p>갱신은 한 단위 처리의 완료를 의미하므로 lease를 즉시 해제한다.
     * lease를 유지하면 RETRY_WAIT 행이 lease 만료까지 재시도되지 못한다.</p>
     * <p>부모 Run이 취소·오류·전역 deadline 실패로 끝난 뒤에는 새 RETRY_WAIT를 만들지
     * 않는다. 반면 HTTP 응답으로 확인한 SUCCEEDED·DEAD는 실제 결과를 잃지 않도록
     * terminal 부모에서도 확정한다. 호출자는 이 UPDATE 전에 부모 Run을 잠가야 한다.</p>
     *
     * @param delivery 갱신할 occurrence
     * @return 갱신 성공 여부
     */
    @Override
    public boolean update(CallbackDelivery delivery) {
        int updated = jdbcClient.sql("""
                        UPDATE callback_delivery d
                        SET status = :status,
                            next_attempt_at = :nextAttemptAt,
                            attempt_count = :attemptCount,
                            last_failure_code = :lastFailureCode,
                            lease_owner = NULL,
                            lease_until = NULL,
                            version = d.version + 1,
                            completed_at = :completedAt
                        FROM scenario_run r
                        WHERE d.id = :id
                          AND d.version = :expectedVersion
                          AND r.id = d.run_id
                          AND (
                              :status <> 'RETRY_WAIT'
                              OR (
                                  r.status NOT IN ('CANCELLED', 'ERROR')
                                  AND NOT (r.status = 'FAILED'
                                           AND r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED')
                              )
                          )
                        """)
                .param("status", delivery.status().name())
                .param("nextAttemptAt", Timestamp.from(delivery.nextAttemptAt()))
                .param("attemptCount", delivery.attemptCount())
                .param("lastFailureCode", delivery.lastFailureCode())
                .param("completedAt", delivery.completedAt() == null ? null
                        : Timestamp.from(delivery.completedAt()))
                .param("id", delivery.id())
                .param("expectedVersion", delivery.version())
                .update();
        return updated > 0;
    }

    /**
     * 식별자로 delivery를 조회한다.
     */
    private Optional<CallbackDelivery> findById(String id) {
        return jdbcClient.sql("""
                        SELECT id, run_id, callback_event_id, occurrence, status, scheduled_at,
                               next_attempt_at, attempt_count, maximum_attempts, lease_owner,
                               lease_until, last_failure_code, request_snapshot::text, version, completed_at
                        FROM callback_delivery
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(new DeliveryRowMapper())
                .optional();
    }

    /**
     * callback_delivery 행을 도메인 record로 변환한다.
     *
     * <p>책임: SQL 시각·상태 타입을 도메인 경계로 옮긴다.</p>
     */
    private final class DeliveryRowMapper implements RowMapper<CallbackDelivery> {

        /**
         * 한 행을 CallbackDelivery로 변환한다.
         *
         * @param rs 결과셋
         * @param rowNum 행 번호
         * @return 변환된 occurrence
         * @throws SQLException 컬럼 접근 실패 시 발생한다
         */
        @Override
        public CallbackDelivery mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp leaseUntil = rs.getTimestamp("lease_until");
            Timestamp completedAt = rs.getTimestamp("completed_at");
            return new CallbackDelivery(
                    rs.getString("id"),
                    rs.getString("run_id"),
                    rs.getString("callback_event_id"),
                    rs.getInt("occurrence"),
                    DeliveryStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("scheduled_at").toInstant(),
                    rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getInt("attempt_count"),
                    rs.getInt("maximum_attempts"),
                    rs.getString("lease_owner"),
                    leaseUntil == null ? null : leaseUntil.toInstant(),
                    rs.getString("last_failure_code"),
                    jsonb.toTree(rs.getString("request_snapshot")),
                    rs.getLong("version"),
                    completedAt == null ? null : completedAt.toInstant());
        }
    }
}
