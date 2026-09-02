package io.outcomeprobe.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.adapter.out.persistence.JdbcEvidenceStore;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.foundation.PostgresqlTimestamp;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Evidence JDBC 조회가 실제 관측 시각 경계를 SQL에 보존하는지 검증한다. */
class JdbcEvidenceStoreTest {

    /**
     * await 전용 존재 조회는 observed_at 이하 조건과 PostgreSQL 정규화 cutoff를 사용해야 한다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void existsObservedAtOrBeforeBindsInclusiveNormalizedObservedAtCutoff() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Long> query =
                (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        doReturn(query).when(statement).query(Long.class);
        when(query.single()).thenReturn(1L);
        JdbcEvidenceStore store = new JdbcEvidenceStore(
                jdbc, mock(IdGenerator.class), mock(ClockProvider.class), new ObjectMapper());
        Instant cutoff = Instant.parse("2026-08-07T00:00:00.123456999Z");
        EvidenceMatcher matcher = new EvidenceMatcher(
                EvidenceType.PROVIDER_OPERATION_COMMITTED, Map.of("operation", "charge"));

        boolean exists = store.existsObservedAtOrBefore("run-1", matcher, cutoff);

        assertThat(exists).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains(
                "WHERE run_id = :runId AND type = :type",
                "attributes @> :attributes",
                "observed_at <= :observedAtInclusive");
        verify(statement).param("observedAtInclusive",
                Timestamp.from(PostgresqlTimestamp.normalize(cutoff)));
    }
}
