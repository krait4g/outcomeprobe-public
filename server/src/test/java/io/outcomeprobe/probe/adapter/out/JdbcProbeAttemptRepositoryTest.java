package io.outcomeprobe.probe.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.probe.ProbeAttempt;
import io.outcomeprobe.probe.ProbeAttemptIntent;
import io.outcomeprobe.probe.ProbeAttemptStart;
import io.outcomeprobe.probe.ProbeAttemptStartState;
import io.outcomeprobe.probe.ProbeDecision;
import io.outcomeprobe.probe.ProbeRunFence;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link JdbcProbeAttemptRepository}의 intent·완료·중단 SQL 계약을 비DB mock으로 검증한다.
 *
 * <p>검출 대상 결함: unique 충돌 뒤 후보 id 반환, HTTP 전 응답 필드 저장, 부분 결과 행
 * 재사용·덮어쓰기, 결과가 없는 중단을 성공으로 오인하는 회귀.</p>
 */
class JdbcProbeAttemptRepositoryTest {

    /** 부모 Run 잠금은 모든 경계에서 R을 먼저 잠그고 lease token을 검증해야 한다. */
    @ParameterizedTest
    @EnumSource(ProbeRunFence.Boundary.class)
    @SuppressWarnings("unchecked")
    void parentRunFenceUsesBlockingForUpdateAndExactLease(
            ProbeRunFence.Boundary boundary) {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> query =
                (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of("run-1"));
        JdbcProbeAttemptRepository repository = new JdbcProbeAttemptRepository(
                jdbc, mock(IdGenerator.class), new ObjectMapper());
        Instant fencedAt = Instant.parse("2026-08-07T01:00:01Z");
        Instant boundaryAt = boundary == ProbeRunFence.Boundary.COMPLETE
                ? Instant.parse("2026-08-07T01:00:00Z") : fencedAt;
        ProbeRunFence fence = new ProbeRunFence(
                "run-1", 7L, "worker-1",
                Instant.parse("2026-08-07T01:00:10Z"),
                fencedAt, boundaryAt, boundary);

        boolean locked = repository.lockRunForProbeMutation(fence);

        assertThat(locked).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue())
                .contains("FROM scenario_run r", "r.id = :runId", "FOR UPDATE")
                .contains("r.version = :expectedRunVersion")
                .contains("r.lease_owner = :expectedLeaseOwner")
                .contains("r.lease_until = :expectedLeaseUntil")
                .contains("r.lease_until >= :fencedAt")
                .contains("r.status IN ('QUEUED', 'PREPARING', 'RUNNING', 'ASSERTING')")
                .contains("CASE :boundary", "WHEN 'BEGIN'", "WHEN 'COMPLETE'")
                .contains("WHEN 'ACTIVE_INTERRUPT'", "WHEN 'CANCEL_INTERRUPT'")
                .contains("WHEN 'DEADLINE_INTERRUPT'")
                .contains(":boundaryAt < r.deadline_at", ":boundaryAt <= r.deadline_at",
                        "r.deadline_at < :boundaryAt")
                .doesNotContain("SKIP LOCKED", "NOWAIT");
        verify(statement).param("runId", "run-1");
        verify(statement).param("expectedRunVersion", 7L);
        verify(statement).param("expectedLeaseOwner", "worker-1");
        verify(statement).param("expectedLeaseUntil",
                Timestamp.from(Instant.parse("2026-08-07T01:00:10Z")));
        verify(statement).param("fencedAt", Timestamp.from(fencedAt));
        verify(statement).param("boundaryAt", Timestamp.from(boundaryAt));
        verify(statement).param("boundary", boundary.name());
    }

    /** 부모 Run이 fence를 통과하지 못하면 자식 쓰기 권한을 반환하지 않아야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void missingOrStaleParentRunReturnsFalse() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> query =
                (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());
        JdbcProbeAttemptRepository repository = new JdbcProbeAttemptRepository(
                jdbc, mock(IdGenerator.class), new ObjectMapper());

        boolean locked = repository.lockRunForProbeMutation(new ProbeRunFence(
                "run-1", 7L, "worker-1",
                Instant.parse("2026-08-07T01:00:10Z"),
                Instant.parse("2026-08-07T01:00:01Z"),
                Instant.parse("2026-08-07T01:00:01Z"),
                ProbeRunFence.Boundary.BEGIN));

        assertThat(locked).isFalse();
    }

    /** 부모 lock과 모든 자식 mutation은 반드시 상위 Probe transaction 안에서만 호출한다. */
    @Test
    void probeMutationsRequireExistingTransaction() throws Exception {
        List<Method> methods = List.of(
                JdbcProbeAttemptRepository.class.getMethod(
                        "lockRunForProbeMutation", ProbeRunFence.class),
                JdbcProbeAttemptRepository.class.getMethod(
                        "begin", ProbeAttemptIntent.class),
                JdbcProbeAttemptRepository.class.getMethod(
                        "complete", ProbeAttempt.class),
                JdbcProbeAttemptRepository.class.getMethod(
                        "interrupt", ProbeAttemptIntent.class, String.class));

        for (Method method : methods) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(method.getName()).isNotNull();
            assertThat(transactional.propagation())
                    .as(method.getName()).isEqualTo(Propagation.MANDATORY);
        }
    }

    /** 신규 begin은 응답 필드를 NULL로 INSERT하고 SELECT한 권위 intent를 반환해야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void beginCreatesEmptyIntentAndReturnsAuthoritativeRow() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec insert = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<ProbeAttemptStart> query =
                (JdbcClient.MappedQuerySpec<ProbeAttemptStart>) mock(
                        JdbcClient.MappedQuerySpec.class);
        IdGenerator ids = mock(IdGenerator.class);
        ResultSet resultSet = mock(ResultSet.class);
        AtomicReference<RowMapper<ProbeAttemptStart>> mapper = new AtomicReference<>();
        Instant requestedAt = Instant.parse("2026-08-07T01:00:00Z");
        when(jdbc.sql(anyString())).thenReturn(insert, select);
        when(insert.param(anyString(), any())).thenReturn(insert);
        when(select.param(anyString(), any())).thenReturn(select);
        when(insert.update()).thenReturn(1);
        doAnswer(invocation -> {
            mapper.set(invocation.getArgument(0));
            return query;
        }).when(select).query(ArgumentMatchers.<RowMapper<ProbeAttemptStart>>any());
        when(query.single()).thenAnswer(invocation -> mapper.get().mapRow(resultSet, 0));
        when(ids.nextId()).thenReturn("candidate-attempt");
        when(resultSet.getString("id")).thenReturn("candidate-attempt");
        when(resultSet.getString("run_id")).thenReturn("run-1");
        when(resultSet.getString("step_id")).thenReturn("step-1");
        when(resultSet.getInt("attempt_number")).thenReturn(1);
        when(resultSet.getTimestamp("requested_at")).thenReturn(Timestamp.from(requestedAt));
        when(resultSet.getTimestamp("responded_at")).thenReturn(null);
        when(resultSet.getString("decision")).thenReturn(null);
        JdbcProbeAttemptRepository repository =
                new JdbcProbeAttemptRepository(jdbc, ids, new ObjectMapper());

        ProbeAttemptStart actual = repository.begin(
                new ProbeAttemptIntent(null, "run-1", "step-1", 1, requestedAt));

        assertThat(actual.state()).isEqualTo(ProbeAttemptStartState.CREATED);
        assertThat(actual.intent()).isEqualTo(
                new ProbeAttemptIntent("candidate-attempt", "run-1", "step-1", 1, requestedAt));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).sql(sql.capture());
        assertThat(sql.getAllValues().get(0))
                .contains("ON CONFLICT (run_id, step_id, attempt_number) DO NOTHING")
                .contains(":requestedAt, NULL", "response_body")
                .contains("NULL, NULL, NULL, NULL, NULL, NULL");
        assertThat(sql.getAllValues().get(1))
                .contains("SELECT id, run_id, step_id, attempt_number, requested_at")
                .contains("responded_at, status_code, response_hash, response_body")
                .contains("assertion_results, decision, failure_code");
    }

    /**
     * unique 충돌은 최초 행의 id·requestedAt을 반환하고 결과 컬럼 하나라도 있으면 덮어쓰지 않는다.
     */
    @ParameterizedTest
    @MethodSource("existingRowStates")
    @SuppressWarnings("unchecked")
    void beginClassifiesAuthoritativeExistingRow(
            Timestamp respondedAt,
            Object statusCode,
            String responseHash,
            Object responseBody,
            Object assertionResults,
            String decision,
            String failureCode,
            ProbeAttemptStartState expectedState)
            throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec insert = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<ProbeAttemptStart> query =
                (JdbcClient.MappedQuerySpec<ProbeAttemptStart>) mock(
                        JdbcClient.MappedQuerySpec.class);
        IdGenerator ids = mock(IdGenerator.class);
        ResultSet resultSet = mock(ResultSet.class);
        AtomicReference<RowMapper<ProbeAttemptStart>> mapper = new AtomicReference<>();
        Instant candidateTime = Instant.parse("2026-08-07T01:00:30Z");
        Instant authoritativeTime = Instant.parse("2026-08-07T01:00:00Z");
        when(jdbc.sql(anyString())).thenReturn(insert, select);
        when(insert.param(anyString(), any())).thenReturn(insert);
        when(select.param(anyString(), any())).thenReturn(select);
        when(insert.update()).thenReturn(0);
        doAnswer(invocation -> {
            mapper.set(invocation.getArgument(0));
            return query;
        }).when(select).query(ArgumentMatchers.<RowMapper<ProbeAttemptStart>>any());
        when(query.single()).thenAnswer(invocation -> mapper.get().mapRow(resultSet, 0));
        when(ids.nextId()).thenReturn("discarded-candidate-id");
        when(resultSet.getString("id")).thenReturn("authoritative-attempt");
        when(resultSet.getString("run_id")).thenReturn("run-1");
        when(resultSet.getString("step_id")).thenReturn("step-1");
        when(resultSet.getInt("attempt_number")).thenReturn(1);
        when(resultSet.getTimestamp("requested_at"))
                .thenReturn(Timestamp.from(authoritativeTime));
        when(resultSet.getTimestamp("responded_at")).thenReturn(respondedAt);
        when(resultSet.getObject("status_code")).thenReturn(statusCode);
        when(resultSet.getString("response_hash")).thenReturn(responseHash);
        when(resultSet.getObject("response_body")).thenReturn(responseBody);
        when(resultSet.getObject("assertion_results")).thenReturn(assertionResults);
        when(resultSet.getString("decision")).thenReturn(decision);
        when(resultSet.getString("failure_code")).thenReturn(failureCode);
        JdbcProbeAttemptRepository repository =
                new JdbcProbeAttemptRepository(jdbc, ids, new ObjectMapper());

        ProbeAttemptStart actual = repository.begin(
                new ProbeAttemptIntent(null, "run-1", "step-1", 1, candidateTime));

        assertThat(actual.state()).isEqualTo(expectedState);
        assertThat(actual.intent().id()).isEqualTo("authoritative-attempt");
        assertThat(actual.intent().requestedAt()).isEqualTo(authoritativeTime);
    }

    /** 완전히 빈 intent와 결과 컬럼 하나만 채워진 부분 행 조합을 제공한다. */
    private static Stream<Arguments> existingRowStates() {
        return Stream.of(
                Arguments.arguments(
                        null, null, null, null, null, null, null,
                        ProbeAttemptStartState.EXISTING_IN_FLIGHT),
                Arguments.arguments(
                        Timestamp.from(Instant.parse("2026-08-07T01:00:01Z")),
                        null, null, null, null, null, null,
                        ProbeAttemptStartState.EXISTING_FINALIZED),
                Arguments.arguments(
                        null, 200, null, null, null, null, null,
                        ProbeAttemptStartState.EXISTING_FINALIZED),
                Arguments.arguments(
                        null, null, "sha256", null, null, null, null,
                        ProbeAttemptStartState.EXISTING_FINALIZED),
                Arguments.arguments(
                        null, null, null, "{\"legacy\":true}", null, null, null,
                        ProbeAttemptStartState.EXISTING_FINALIZED),
                Arguments.arguments(
                        null, null, null, null, "[]", null, null,
                        ProbeAttemptStartState.EXISTING_FINALIZED),
                Arguments.arguments(
                        null, null, null, null, null, "PENDING", null,
                        ProbeAttemptStartState.EXISTING_FINALIZED),
                Arguments.arguments(
                        null, null, null, null, null, null, "LEGACY_PARTIAL",
                        ProbeAttemptStartState.EXISTING_FINALIZED));
    }

    /** find는 INSERT 없이 identity만 조회해 권위 행 또는 empty를 반환해야 한다. */
    @Test
    @SuppressWarnings("unchecked")
    void findReturnsAuthoritativeOptionalWithoutCreatingCandidate() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<ProbeAttemptStart> query =
                (JdbcClient.MappedQuerySpec<ProbeAttemptStart>) mock(
                        JdbcClient.MappedQuerySpec.class);
        IdGenerator ids = mock(IdGenerator.class);
        ResultSet resultSet = mock(ResultSet.class);
        AtomicReference<RowMapper<ProbeAttemptStart>> mapper = new AtomicReference<>();
        Instant candidateTime = Instant.parse("2026-08-07T01:00:30Z");
        Instant authoritativeTime = Instant.parse("2026-08-07T01:00:00Z");
        when(jdbc.sql(anyString())).thenReturn(select);
        when(select.param(anyString(), any())).thenReturn(select);
        doAnswer(invocation -> {
            mapper.set(invocation.getArgument(0));
            return query;
        }).when(select).query(ArgumentMatchers.<RowMapper<ProbeAttemptStart>>any());
        when(query.optional())
                .thenAnswer(invocation -> Optional.of(mapper.get().mapRow(resultSet, 0)))
                .thenReturn(Optional.empty());
        when(resultSet.getString("id")).thenReturn("authoritative-attempt");
        when(resultSet.getString("run_id")).thenReturn("run-1");
        when(resultSet.getString("step_id")).thenReturn("step-1");
        when(resultSet.getInt("attempt_number")).thenReturn(2);
        when(resultSet.getTimestamp("requested_at"))
                .thenReturn(Timestamp.from(authoritativeTime));
        when(resultSet.getTimestamp("responded_at")).thenReturn(null);
        when(resultSet.getString("decision")).thenReturn(null);
        JdbcProbeAttemptRepository repository =
                new JdbcProbeAttemptRepository(jdbc, ids, new ObjectMapper());
        ProbeAttemptIntent candidate = new ProbeAttemptIntent(
                "ignored-candidate-id", "run-1", "step-1", 2, candidateTime);

        Optional<ProbeAttemptStart> found = repository.find(candidate);
        Optional<ProbeAttemptStart> absent = repository.find(candidate);

        assertThat(found).contains(new ProbeAttemptStart(
                new ProbeAttemptIntent("authoritative-attempt", "run-1", "step-1", 2,
                        authoritativeTime), ProbeAttemptStartState.EXISTING_IN_FLIGHT));
        assertThat(absent).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).sql(sql.capture());
        assertThat(sql.getAllValues()).allSatisfy(value -> assertThat(value)
                .contains("responded_at, status_code, response_hash, response_body")
                .contains("assertion_results, decision, failure_code")
                .contains("WHERE run_id = :runId", "step_id = :stepId",
                        "attempt_number = :attemptNumber")
                .doesNotContain("INSERT", ":id", ":requestedAt"));
        verify(ids, never()).nextId();
    }

    /** complete는 비어 있는 같은 행만 갱신하고 response_body를 계속 NULL로 유지해야 한다. */
    @Test
    void completeUsesSingleConditionalUpdateWithoutResponseBody() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        JdbcProbeAttemptRepository repository = new JdbcProbeAttemptRepository(
                jdbc, mock(IdGenerator.class), new ObjectMapper());
        ProbeAttempt attempt = completedAttempt();

        repository.complete(attempt);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue())
                .contains("UPDATE probe_attempt", "response_body = NULL")
                .contains("WHERE id = :id")
                .contains("responded_at IS NULL", "status_code IS NULL")
                .contains("response_hash IS NULL", "response_body IS NULL")
                .contains("assertion_results IS NULL", "decision IS NULL")
                .contains("failure_code IS NULL");
        verify(statement).param("id", "attempt-1");
        verify(statement).param("decision", ProbeDecision.PENDING.name());
    }

    /** complete 조건부 UPDATE가 0행이면 완료를 성공처럼 반환하지 않아야 한다. */
    @Test
    void completeFailsClosedWhenIntentWasAlreadyFinalized() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(0);
        JdbcProbeAttemptRepository repository = new JdbcProbeAttemptRepository(
                jdbc, mock(IdGenerator.class), new ObjectMapper());

        assertThatThrownBy(() -> repository.complete(completedAttempt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정확히 한 행");
    }

    /** interrupt는 미확정 같은 행만 PENDING으로 바꾸고 적용 여부를 그대로 반환해야 한다. */
    @Test
    void interruptUsesConditionalPendingUpdateAndReportsSuccess() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1, 0);
        JdbcProbeAttemptRepository repository = new JdbcProbeAttemptRepository(
                jdbc, mock(IdGenerator.class), new ObjectMapper());
        ProbeAttemptIntent intent = new ProbeAttemptIntent(
                "attempt-1", "run-1", "step-1", 1, Instant.EPOCH);

        assertThat(repository.interrupt(intent, "PROBE_RESULT_NOT_COMMITTED")).isTrue();
        assertThat(repository.interrupt(intent, "PROBE_RESULT_NOT_COMMITTED")).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).sql(sql.capture());
        assertThat(sql.getAllValues()).allSatisfy(value -> assertThat(value)
                .contains("SET decision = 'PENDING'")
                .contains("responded_at IS NULL", "status_code IS NULL")
                .contains("response_hash IS NULL", "response_body IS NULL")
                .contains("assertion_results IS NULL", "decision IS NULL")
                .contains("failure_code IS NULL")
                .doesNotContain("SET responded_at"));
        verify(statement, times(2))
                .param("failureCode", "PROBE_RESULT_NOT_COMMITTED");
    }

    /** 저장된 id와 원문 body가 없는 완료 Probe attempt fixture를 만든다. */
    private ProbeAttempt completedAttempt() {
        return new ProbeAttempt("attempt-1", "run-1", "step-1", 1,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 200, "sha256",
                List.of(), ProbeDecision.PENDING, null);
    }
}
