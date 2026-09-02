package io.outcomeprobe.delivery.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.delivery.CallbackDelivery;
import io.outcomeprobe.delivery.CallbackEvent;
import io.outcomeprobe.delivery.DeliveryStatus;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Callback JDBC adapter의 권위 행 재사용과 만료 lease 복구 SQL 계약을 검증한다.
 *
 * <p>실제 PostgreSQL 문법·격리는 다루지 않는 비DB 테스트다. 과거처럼 INSERT 결과를
 * 확인하지 않고 새 UUID를 반환하면 기존 사건을 참조하는 Delivery가 FK 오류를 내는 회귀를
 * 직접 검출한다.</p>
 */
class JdbcCallbackDeliveryRepositoryTest {

    /** 부모 Run 잠금 메서드는 외부 transaction 없이 단독 호출될 수 없어야 한다. */
    @Test
    void parentRunLocksRequireExistingTransaction() throws NoSuchMethodException {
        Transactional single = JdbcCallbackDeliveryRepository.class
                .getMethod("lockRunForDeliveryMutation", String.class)
                .getAnnotation(Transactional.class);
        Transactional recovery = JdbcCallbackDeliveryRepository.class
                .getMethod("lockNextRunForExpiredRecovery", Instant.class)
                .getAnnotation(Transactional.class);
        Transactional stranded = JdbcCallbackDeliveryRepository.class
                .getMethod("cancelStrandedPendingForTerminalRun", String.class, Instant.class)
                .getAnnotation(Transactional.class);
        Transactional terminalUnknown = JdbcCallbackDeliveryRepository.class
                .getMethod("markExpiredForTerminalRunUnknown", String.class, Instant.class)
                .getAnnotation(Transactional.class);
        Transactional exhaustedUnknown = JdbcCallbackDeliveryRepository.class
                .getMethod("markExpiredExhaustedAttemptsUnknown", String.class, Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(single).isNotNull();
        assertThat(recovery).isNotNull();
        assertThat(stranded).isNotNull();
        assertThat(terminalUnknown).isNotNull();
        assertThat(exhaustedUnknown).isNotNull();
        assertThat(single.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(recovery.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(stranded.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(terminalUnknown.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(exhaustedUnknown.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    /** 단건 Callback 쓰기는 부모 scenario_run을 먼저 FOR UPDATE 잠가야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void lockRunForDeliveryMutationUsesParentRowLock() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> query =
                (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement).query(String.class);
        when(query.optional()).thenReturn(java.util.Optional.of("run-1"));
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());

        assertThat(repository.lockRunForDeliveryMutation("run-1")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains(
                "SELECT id", "FROM scenario_run", "WHERE id = :runId", "FOR UPDATE");
    }

    /** recovery는 결정적 순서에서 부모 하나만 잠근 뒤 해당 Delivery를 갱신해야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void lockNextRunForExpiredRecoveryUsesOneDeterministicParentLock() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> query =
                (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement).query(String.class);
        when(query.optional()).thenReturn(java.util.Optional.of("run-a"));
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());

        assertThat(repository.lockNextRunForExpiredRecovery(
                Instant.parse("2026-08-06T03:00:00Z"))).contains("run-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains(
                "FROM scenario_run r",
                "d.status IN ('SCHEDULED', 'RETRY_WAIT')",
                "d.status IN ('LEASED', 'ATTEMPTING')",
                "d.attempt_count >= d.maximum_attempts",
                "r.status IN ('CANCELLED', 'ERROR')",
                "r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED'",
                "ORDER BY r.id",
                "LIMIT 1",
                "FOR UPDATE OF r SKIP LOCKED")
                .doesNotContain("STEP_TIMEOUT_EXCEEDED");
    }

    /** 전송 직전 fence는 lease 소유권과 부모 Run의 전송 차단 상태를 함께 확인해야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void beginAttemptRequiresAuthoritativeLeaseAndDeliveryAllowedParent() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<CallbackDelivery> query =
                (JdbcClient.MappedQuerySpec<CallbackDelivery>) mock(
                        JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement)
                .query(ArgumentMatchers.<RowMapper<CallbackDelivery>>any());
        CallbackDelivery attempting = mock(CallbackDelivery.class);
        when(query.optional()).thenReturn(java.util.Optional.of(attempting));
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery leased = new CallbackDelivery(
                "delivery-1", "run-1", "event-1", 1, DeliveryStatus.LEASED,
                now.minusSeconds(1), now.minusSeconds(1), 0, 3, "worker-1",
                now.plusSeconds(10), null, new ObjectMapper().createObjectNode(), 7L, null);

        assertThat(repository.beginAttempt(leased, now, 10_000L)).containsSame(attempting);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains(
                "UPDATE callback_delivery d",
                "SET status = 'ATTEMPTING'",
                "attempt_count = d.attempt_count + 1",
                "version = d.version + 1",
                "FROM scenario_run r",
                "r.id = d.run_id",
                "d.status = 'LEASED'",
                "d.lease_owner = :leaseOwner",
                "d.lease_until >= :now",
                "d.version = :expectedVersion",
                "d.attempt_count < d.maximum_attempts",
                "r.status NOT IN ('CANCELLED', 'ERROR')",
                "r.status = 'FAILED'",
                "r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED'",
                "RETURNING d.id")
                .doesNotContain("STEP_TIMEOUT_EXCEEDED");
    }

    /** terminal 부모에서는 RETRY_WAIT만 거부하고 실제 최종 결과는 허용하는 SQL fence여야 한다. */
    @Test
    void updateGuardsOnlyRetryWaitWithGlobalTerminalParentState() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(0);
        ObjectMapper mapper = new ObjectMapper();
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), mapper);
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery retryWait = new CallbackDelivery(
                "delivery-retry", "run-terminal", "event-1", 1,
                DeliveryStatus.RETRY_WAIT, now.minusSeconds(1), now.plusSeconds(30),
                1, 3, null, null, "HTTP_503", mapper.createObjectNode(), 7L, null);

        assertThat(repository.update(retryWait)).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        verify(statement).param("status", DeliveryStatus.RETRY_WAIT.name());
        assertThat(sql.getValue()).contains(
                "UPDATE callback_delivery d",
                "FROM scenario_run r",
                "r.id = d.run_id",
                ":status <> 'RETRY_WAIT'",
                "r.status NOT IN ('CANCELLED', 'ERROR')",
                "r.status = 'FAILED'",
                "r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED'",
                "d.version = :expectedVersion")
                .doesNotContain("STEP_TIMEOUT_EXCEEDED");
    }

    /** terminal Run 전환은 due 상태와 HTTP 전 LEASED만 CANCELLED로 닫아야 한다. */
    @Test
    void cancelPendingForRunExcludesInFlightAndFinalDeliveries() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(2);
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());

        int cancelled = repository.cancelPendingForRun(
                "run-terminal", Instant.parse("2026-08-06T03:00:00Z"));

        assertThat(cancelled).isEqualTo(2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue())
                .contains(
                        "SET status = 'CANCELLED'",
                        "lease_owner = NULL",
                        "lease_until = NULL",
                        "completed_at = :completedAt",
                        "version = version + 1",
                        "status IN ('SCHEDULED', 'RETRY_WAIT', 'LEASED')")
                .doesNotContain("'ATTEMPTING'", "'SUCCEEDED'", "'DEAD'");
    }

    /** 업그레이드 전 terminal 부모의 미시작 행은 선잠금한 runId 안에서만 취소해야 한다. */
    @Test
    void cancelStrandedPendingUsesExactTerminalParentScope() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(2);
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());
        Instant now = Instant.parse("2026-08-06T03:00:00Z");

        assertThat(repository.cancelStrandedPendingForTerminalRun("run-terminal", now))
                .isEqualTo(2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        verify(statement).param("runId", "run-terminal");
        assertThat(sql.getValue()).contains(
                "UPDATE callback_delivery d",
                "FROM scenario_run r",
                "d.run_id = :runId",
                "r.id = d.run_id",
                "d.status IN ('SCHEDULED', 'RETRY_WAIT')",
                "r.status IN ('CANCELLED', 'ERROR')",
                "r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED'",
                "SET status = 'CANCELLED'")
                .doesNotContain("'LEASED'", "'ATTEMPTING'", "STEP_TIMEOUT_EXCEEDED");
    }

    /** terminal parent의 만료 in-flight만 재전송 없이 UNKNOWN으로 복구해야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void markExpiredForTerminalRunUnknownUsesExactLockedParentScope() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<CallbackDelivery> query =
                (JdbcClient.MappedQuerySpec<CallbackDelivery>) mock(
                        JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement)
                .query(ArgumentMatchers.<RowMapper<CallbackDelivery>>any());
        when(query.list()).thenReturn(java.util.List.of(mock(CallbackDelivery.class)));
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());

        java.util.List<CallbackDelivery> recovered =
                repository.markExpiredForTerminalRunUnknown(
                        "run-terminal", Instant.parse("2026-08-06T03:00:00Z"));

        assertThat(recovered).hasSize(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        verify(statement).param("runId", "run-terminal");
        assertThat(sql.getValue()).contains(
                "SET status = 'UNKNOWN'",
                "d.run_id = :runId",
                "d.status IN ('LEASED', 'ATTEMPTING')",
                "d.lease_until < :now",
                "r.status IN ('CANCELLED', 'ERROR')",
                "r.status = 'FAILED'",
                "r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED'",
                "DELIVERY_OUTCOME_UNKNOWN_AFTER_RUN_TERMINATION",
                "version = d.version + 1",
                "RETURNING d.id")
                .doesNotContain("STEP_TIMEOUT_EXCEEDED");
    }

    /** 마지막 attempt 뒤 crash는 상한을 우회해 다시 lease되지 않고 UNKNOWN이어야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void markExpiredExhaustedAttemptsUnknownPreservesAmbiguousOutcome() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<CallbackDelivery> query =
                (JdbcClient.MappedQuerySpec<CallbackDelivery>) mock(
                        JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement)
                .query(ArgumentMatchers.<RowMapper<CallbackDelivery>>any());
        when(query.list()).thenReturn(java.util.List.of(mock(CallbackDelivery.class)));
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());

        assertThat(repository.markExpiredExhaustedAttemptsUnknown(
                "run-active", Instant.parse("2026-08-06T03:00:00Z"))).hasSize(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        verify(statement).param("runId", "run-active");
        assertThat(sql.getValue()).contains(
                "SET status = 'UNKNOWN'",
                "d.run_id = :runId",
                "d.status IN ('LEASED', 'ATTEMPTING')",
                "d.lease_until < :now",
                "d.attempt_count >= d.maximum_attempts",
                "DELIVERY_OUTCOME_UNKNOWN_AFTER_ATTEMPT_LIMIT",
                "version = d.version + 1",
                "RETURNING d.id");
    }

    /** 만료된 LEASED occurrence도 due 신규 전달과 함께 원자 재취득 후보여야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void leaseNextDueIncludesExpiredLeasedOccurrenceRecoveryBranch() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> query =
                (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement).query(String.class);
        when(query.optional()).thenReturn(java.util.Optional.empty());
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());

        assertThat(repository.leaseNextDue(
                "worker-recovery", Instant.parse("2026-08-06T02:00:00Z"), 10_000L))
                .isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue())
                .contains(
                        "WITH locked_run AS MATERIALIZED",
                        "JOIN LATERAL",
                        "d.status IN ('SCHEDULED', 'RETRY_WAIT')",
                        "d.attempt_count < d.maximum_attempts",
                        "d.next_attempt_at <= :now",
                        "d.status IN ('LEASED', 'ATTEMPTING') AND d.lease_until < :now",
                        "r.status NOT IN ('CANCELLED', 'ERROR')",
                        "r.status = 'FAILED'",
                        "r.problem_code = 'SCENARIO_TIMEOUT_EXCEEDED'",
                        "MIN(candidate.next_attempt_at) AS earliest_due",
                        "ORDER BY due.earliest_due, r.id",
                        "FOR UPDATE OF r SKIP LOCKED",
                        "FOR UPDATE OF d SKIP LOCKED")
                .doesNotContain("STEP_TIMEOUT_EXCEEDED");
        assertThat(sql.getValue().indexOf("FOR UPDATE OF r SKIP LOCKED"))
                .isLessThan(sql.getValue().indexOf("FOR UPDATE OF d SKIP LOCKED"));
    }

    /** lease 만료 시각은 recovery 시작 시각이 아니라 호출자가 넘긴 최신 획득 시각 기준이다. */
    @Test
    @SuppressWarnings("unchecked")
    void leaseNextDueBindsLeaseUntilFromAcquisitionClock() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> idQuery =
                (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);
        JdbcClient.MappedQuerySpec<CallbackDelivery> deliveryQuery =
                (JdbcClient.MappedQuerySpec<CallbackDelivery>) mock(
                        JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(idQuery).when(statement).query(String.class);
        doReturn(deliveryQuery).when(statement)
                .query(ArgumentMatchers.<RowMapper<CallbackDelivery>>any());
        when(idQuery.optional()).thenReturn(java.util.Optional.of("delivery-1"));
        CallbackDelivery leased = mock(CallbackDelivery.class);
        when(deliveryQuery.optional()).thenReturn(java.util.Optional.of(leased));
        JdbcCallbackDeliveryRepository repository = new JdbcCallbackDeliveryRepository(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());
        Instant leaseAcquiredAt = Instant.parse("2026-08-06T02:00:12Z");

        assertThat(repository.leaseNextDue("worker-recovery", leaseAcquiredAt, 10_000L))
                .containsSame(leased);

        verify(statement).param("now", Timestamp.from(leaseAcquiredAt));
        verify(statement).param(
                "leaseUntil", Timestamp.from(leaseAcquiredAt.plusMillis(10_000L)));
    }

    /** 동일 eventId 충돌 시 최초 저장된 내부 id·payload·hash를 그대로 반환해야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void saveEventReturnsExistingDatabaseRowAndKeepsFirstPayloadSnapshot() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<CallbackEvent> query =
                (JdbcClient.MappedQuerySpec<CallbackEvent>) mock(JdbcClient.MappedQuerySpec.class);
        IdGenerator ids = mock(IdGenerator.class);
        ClockProvider clock = mock(ClockProvider.class);
        ObjectMapper mapper = new ObjectMapper();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement)
                .query(ArgumentMatchers.<RowMapper<CallbackEvent>>any());
        when(ids.nextId()).thenReturn("candidate-new-event-id");
        when(clock.now()).thenReturn(Instant.parse("2026-08-06T01:00:00Z"));
        ObjectNode candidatePayload = mapper.createObjectNode().put("clock", "second-render");
        ObjectNode firstPayload = mapper.createObjectNode().put("clock", "first-render");
        CallbackEvent persisted = new CallbackEvent(
                "existing-event-row-id", "run-1", "track.updated", "evt-1",
                "TRACK_UPDATED", "track", "TS00000042", 3L,
                firstPayload, "first-payload-hash");
        when(query.single()).thenReturn(persisted);
        JdbcCallbackDeliveryRepository repository =
                new JdbcCallbackDeliveryRepository(jdbc, ids, clock, mapper);

        CallbackEvent actual = repository.saveEvent(new CallbackEvent(
                null, "run-1", "track.updated", "evt-1", "TRACK_UPDATED",
                "track", "TS00000042", 3L, candidatePayload, "second-payload-hash"));

        assertThat(actual).isSameAs(persisted);
        assertThat(actual.id()).isEqualTo("existing-event-row-id");
        assertThat(actual.payload()).isEqualTo(firstPayload);
        assertThat(actual.payloadHash()).isEqualTo("first-payload-hash");
        verify(statement, never()).update();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains(
                "ON CONFLICT (run_id, event_id) DO UPDATE", "RETURNING id");
    }

    /** 동일 사건·occurrence 충돌 시 완료된 기존 Delivery 상태를 초기화하지 않아야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void saveDeliveryReturnsExistingOccurrenceWithoutResettingItsState() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<CallbackDelivery> query =
                (JdbcClient.MappedQuerySpec<CallbackDelivery>) mock(JdbcClient.MappedQuerySpec.class);
        IdGenerator ids = mock(IdGenerator.class);
        ClockProvider clock = mock(ClockProvider.class);
        ObjectMapper mapper = new ObjectMapper();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement)
                .query(ArgumentMatchers.<RowMapper<CallbackDelivery>>any());
        when(ids.nextId()).thenReturn("candidate-new-delivery-id");
        Instant firstTime = Instant.parse("2026-08-06T01:00:00Z");
        ObjectNode firstSnapshot = mapper.createObjectNode()
                .put("payloadHash", "first-payload-hash");
        CallbackDelivery persisted = new CallbackDelivery(
                "existing-delivery-row-id", "run-1", "existing-event-row-id", 1,
                DeliveryStatus.SUCCEEDED, firstTime, firstTime, 1, 3,
                null, null, null, firstSnapshot, 4L, firstTime.plusSeconds(1));
        when(query.single()).thenReturn(persisted);
        JdbcCallbackDeliveryRepository repository =
                new JdbcCallbackDeliveryRepository(jdbc, ids, clock, mapper);
        Instant retryTime = firstTime.plusSeconds(30);
        ObjectNode candidateSnapshot = mapper.createObjectNode()
                .put("payloadHash", "second-payload-hash");

        CallbackDelivery actual = repository.saveDelivery(new CallbackDelivery(
                null, "run-1", "existing-event-row-id", 1, DeliveryStatus.SCHEDULED,
                retryTime, retryTime, 0, 3, null, null, null,
                candidateSnapshot, 0L, null));

        assertThat(actual).isSameAs(persisted);
        assertThat(actual.id()).isEqualTo("existing-delivery-row-id");
        assertThat(actual.status()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(actual.requestSnapshot()).isEqualTo(firstSnapshot);
        verify(statement, never()).update();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains(
                "ON CONFLICT (callback_event_id, occurrence) DO UPDATE", "RETURNING id");
    }
}
