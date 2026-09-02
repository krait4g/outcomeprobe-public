package io.outcomeprobe.probe.adapter.out;

import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import io.outcomeprobe.probe.ProbeAttempt;
import io.outcomeprobe.probe.ProbeAttemptEvidenceFactory;
import io.outcomeprobe.probe.ProbeAttemptIntent;
import io.outcomeprobe.probe.ProbeAttemptRepository;
import io.outcomeprobe.probe.ProbeAttemptStart;
import io.outcomeprobe.probe.ProbeAttemptStartState;
import io.outcomeprobe.probe.ProbeRunFence;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * V3 {@code probe_attempt} 테이블에 Probe 관측을 저장하는 adapter다.
 *
 * <p>책임: HTTP 전에 nullable intent를 저장하고, HTTP 뒤 같은 행을 조건부 확정하거나
 * 미확정 중단 상태로 바꾼다.</p>
 * <p>핵심 불변식: {@code response_body}에는 항상 NULL을 넣는다. 원본 응답은 해시만
 * 보존하며 Run·Step·attempt 번호 unique 충돌 때 최초 저장 행을 권위 값으로 반환한다.
 * 결과 컬럼이 하나라도 채워진 기존 행은 불완전한 legacy 행이어도 덮어쓰지 않는다.</p>
 */
@Repository
public class JdbcProbeAttemptRepository implements ProbeAttemptRepository {

    private final JdbcClient jdbcClient;
    private final IdGenerator idGenerator;
    private final JsonbSupport jsonb;

    /**
     * Probe attempt 저장에 필요한 JDBC와 식별자 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param idGenerator attempt 식별자 생성기
     * @param objectMapper assertion 결과 JSON 변환 mapper
     */
    public JdbcProbeAttemptRepository(JdbcClient jdbcClient, IdGenerator idGenerator,
                                      tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.idGenerator = idGenerator;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * Probe 자식 행보다 부모 Run을 먼저 잠그고 lease·terminal 경계를 검증한다.
     *
     * <p>{@code expectedLeaseUntil}까지 비교해 같은 Worker가 lease를 다시 얻은 경우도 이전
     * 작업과 구분한다. {@code FOR UPDATE}에는 SKIP LOCKED나 NOWAIT를 사용하지 않아 concurrent
     * terminal transaction과 반드시 같은 부모 행에서 직렬화한다.</p>
     *
     * @param fence Run lease와 변경 경계
     * @return 부모 잠금과 경계 검증에 성공했으면 true
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockRunForProbeMutation(ProbeRunFence fence) {
        return jdbcClient.sql("""
                        SELECT r.id
                        FROM scenario_run r
                        WHERE r.id = :runId
                          AND r.version = :expectedRunVersion
                          AND r.lease_owner = :expectedLeaseOwner
                          AND r.lease_until = :expectedLeaseUntil
                          AND r.lease_until >= :fencedAt
                          AND r.status IN ('QUEUED', 'PREPARING', 'RUNNING', 'ASSERTING')
                          AND CASE :boundary
                              WHEN 'BEGIN' THEN r.cancel_requested = false
                                  AND :boundaryAt < r.deadline_at
                              WHEN 'COMPLETE' THEN r.cancel_requested = false
                                  AND :boundaryAt <= r.deadline_at
                              WHEN 'ACTIVE_INTERRUPT' THEN r.cancel_requested = false
                                  AND :boundaryAt <= r.deadline_at
                              WHEN 'CANCEL_INTERRUPT' THEN r.cancel_requested = true
                              WHEN 'DEADLINE_INTERRUPT' THEN r.deadline_at < :boundaryAt
                              ELSE false
                          END
                        FOR UPDATE
                        """)
                .param("runId", fence.runId())
                .param("expectedRunVersion", fence.expectedRunVersion())
                .param("expectedLeaseOwner", fence.expectedLeaseOwner())
                .param("expectedLeaseUntil", Timestamp.from(fence.expectedLeaseUntil()))
                .param("fencedAt", Timestamp.from(fence.fencedAt()))
                .param("boundaryAt", Timestamp.from(fence.boundaryAt()))
                .param("boundary", fence.boundary().name())
                .query(String.class)
                .optional()
                .isPresent();
    }

    /**
     * 응답 필드가 전부 NULL인 intent를 먼저 저장하고 unique identity의 권위 행을 조회한다.
     *
     * <p>INSERT 성공 여부만으로 후보 id를 반환하지 않는다. 충돌 여부와 무관하게 다시
     * SELECT해 최초 행의 id·requestedAt·decision을 기준으로 시작 상태를 만든다.</p>
     *
     * @param intent 시작할 Probe attempt 의도
     * @return DB 권위 intent와 시작 상태
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProbeAttemptStart begin(ProbeAttemptIntent intent) {
        String candidateId = intent.id() == null ? idGenerator.nextId() : intent.id();
        requirePersistedId(candidateId);
        int inserted = jdbcClient.sql("""
                        INSERT INTO probe_attempt (
                            id, run_id, step_id, attempt_number, requested_at, responded_at,
                            status_code, response_hash, response_body, assertion_results,
                            decision, failure_code
                        ) VALUES (
                            :id, :runId, :stepId, :attemptNumber, :requestedAt, NULL,
                            NULL, NULL, NULL, NULL, NULL, NULL
                        )
                        ON CONFLICT (run_id, step_id, attempt_number) DO NOTHING
                        """)
                .param("id", candidateId)
                .param("runId", intent.runId())
                .param("stepId", intent.stepId())
                .param("attemptNumber", intent.attemptNumber())
                .param("requestedAt", Timestamp.from(intent.requestedAt()))
                .update();
        if (inserted < 0 || inserted > 1) {
            throw new IllegalStateException(
                    "Probe attempt intent INSERT 결과가 0 또는 1행이 아닙니다: " + inserted);
        }

        return authoritativeQuery(intent, inserted == 1).single();
    }

    /**
     * 새 행을 만들지 않고 같은 Run·Step·attempt identity의 권위 행을 조회한다.
     *
     * @param candidate 조회할 identity 후보, id와 requestedAt은 검색에 사용하지 않음
     * @return 기존 행의 권위 intent와 진행 상태, 행이 없으면 empty
     */
    @Override
    public Optional<ProbeAttemptStart> find(ProbeAttemptIntent candidate) {
        return authoritativeQuery(candidate, false).optional();
    }

    /**
     * identity 세 필드만으로 권위 attempt 행을 읽는 공통 JDBC query를 만든다.
     *
     * @param candidate Run·Step·attempt identity 후보
     * @param created 이번 transaction이 행을 새로 INSERT했으면 true
     * @return 단건·optional 종결 방식을 호출자가 선택할 수 있는 mapped query
     */
    private JdbcClient.MappedQuerySpec<ProbeAttemptStart> authoritativeQuery(
            ProbeAttemptIntent candidate, boolean created) {
        return jdbcClient.sql("""
                        SELECT id, run_id, step_id, attempt_number, requested_at,
                               responded_at, status_code, response_hash, response_body,
                               assertion_results, decision, failure_code
                        FROM probe_attempt
                        WHERE run_id = :runId
                          AND step_id = :stepId
                          AND attempt_number = :attemptNumber
                        """)
                .param("runId", candidate.runId())
                .param("stepId", candidate.stepId())
                .param("attemptNumber", candidate.attemptNumber())
                .query((resultSet, rowNumber) -> {
                    ProbeAttemptIntent authoritative = new ProbeAttemptIntent(
                            resultSet.getString("id"),
                            resultSet.getString("run_id"),
                            resultSet.getString("step_id"),
                            resultSet.getInt("attempt_number"),
                            resultSet.getTimestamp("requested_at").toInstant());
                    Timestamp respondedAt = resultSet.getTimestamp("responded_at");
                    Object statusCode = resultSet.getObject("status_code");
                    String responseHash = resultSet.getString("response_hash");
                    Object responseBody = resultSet.getObject("response_body");
                    Object assertionResults = resultSet.getObject("assertion_results");
                    String decision = resultSet.getString("decision");
                    String failureCode = resultSet.getString("failure_code");
                    ProbeAttemptStartState state = created
                            ? ProbeAttemptStartState.CREATED
                            : existingState(respondedAt, statusCode, responseHash, responseBody,
                                    assertionResults, decision, failureCode);
                    return new ProbeAttemptStart(authoritative, state);
                });
    }

    /**
     * 시작된 intent의 빈 응답 필드를 redaction된 최종 결과로 한 번만 갱신한다.
     *
     * <p>response_body를 SQL literal NULL로 고정해 원문 전달 통로를 만들지 않는다.
     * 조건부 UPDATE가 정확히 한 행이 아니면 중복·오염 가능성을 숨기지 않고 실패한다.</p>
     *
     * @param attempt 확정할 Probe attempt
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(ProbeAttempt attempt) {
        requirePersistedId(attempt.id());
        // 일부 결과만 남은 legacy 행도 새 결과로 덮어쓰지 않도록 완전히 빈 intent만 확정한다.
        int updated = jdbcClient.sql("""
                        UPDATE probe_attempt
                        SET responded_at = :respondedAt,
                            status_code = :statusCode,
                            response_hash = :responseHash,
                            response_body = NULL,
                            assertion_results = :assertionResults,
                            decision = :decision,
                            failure_code = :failureCode
                        WHERE id = :id
                          AND run_id = :runId
                          AND step_id = :stepId
                          AND attempt_number = :attemptNumber
                          AND responded_at IS NULL
                          AND status_code IS NULL
                          AND response_hash IS NULL
                          AND response_body IS NULL
                          AND assertion_results IS NULL
                          AND decision IS NULL
                          AND failure_code IS NULL
                        """)
                .param("id", attempt.id())
                .param("runId", attempt.runId())
                .param("stepId", attempt.stepId())
                .param("attemptNumber", attempt.attemptNumber())
                .param("respondedAt", Timestamp.from(attempt.respondedAt()))
                .param("statusCode", attempt.statusCode())
                .param("responseHash", attempt.responseHash())
                .param("assertionResults", jsonb.ofValue(
                        ProbeAttemptEvidenceFactory.safeAssertions(attempt)))
                .param("decision", attempt.decision().name())
                .param("failureCode", attempt.failureCode())
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "Probe attempt 완료 조건부 UPDATE가 정확히 한 행에 적용되지 않았습니다: "
                            + updated);
        }
    }

    /**
     * 결과 미확정 intent만 PENDING 중단 상태로 조건부 전환한다.
     *
     * @param intent 중단할 DB 권위 intent
     * @param failureCode 중단 원인을 식별하는 안전한 코드
     * @return 정확히 한 행을 중단 처리했으면 true
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean interrupt(ProbeAttemptIntent intent, String failureCode) {
        requirePersistedId(intent.id());
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("Probe attempt 중단 failureCode가 필요합니다.");
        }
        // 부분 기록을 중단 상태로 가장하지 않도록 완전히 빈 intent에만 CAS를 적용한다.
        int updated = jdbcClient.sql("""
                        UPDATE probe_attempt
                        SET decision = 'PENDING',
                            failure_code = :failureCode
                        WHERE id = :id
                          AND run_id = :runId
                          AND step_id = :stepId
                          AND attempt_number = :attemptNumber
                          AND responded_at IS NULL
                          AND status_code IS NULL
                          AND response_hash IS NULL
                          AND response_body IS NULL
                          AND assertion_results IS NULL
                          AND decision IS NULL
                          AND failure_code IS NULL
                        """)
                .param("failureCode", failureCode)
                .param("id", intent.id())
                .param("runId", intent.runId())
                .param("stepId", intent.stepId())
                .param("attemptNumber", intent.attemptNumber())
                .update();
        if (updated < 0 || updated > 1) {
            throw new IllegalStateException(
                    "Probe attempt 중단 조건부 UPDATE 결과가 0 또는 1행이 아닙니다: " + updated);
        }
        return updated == 1;
    }

    /**
     * 기존 행의 모든 결과 컬럼이 비어 있는지 확인해 진행 중과 덮어쓰기 금지 상태로 분류한다.
     *
     * @param respondedAt DB에 저장된 응답 시각, 결과 미확정이면 null
     * @param statusCode DB에 저장된 HTTP 상태, 결과 미확정이면 null
     * @param responseHash DB에 저장된 응답 해시, 결과 미확정이면 null
     * @param responseBody legacy 행에 저장됐을 수 있는 응답 원문, 결과 미확정이면 null
     * @param assertionResults DB에 저장된 assertion 결과, 결과 미확정이면 null
     * @param decision DB에 저장된 decision, 결과 미확정이면 null
     * @param failureCode DB에 저장된 실패 코드, 결과 미확정이면 null
     * @return 기존 행의 시작 상태
     */
    private ProbeAttemptStartState existingState(
            Timestamp respondedAt,
            Object statusCode,
            String responseHash,
            Object responseBody,
            Object assertionResults,
            String decision,
            String failureCode) {
        return respondedAt == null
                && statusCode == null
                && responseHash == null
                && responseBody == null
                && assertionResults == null
                && decision == null
                && failureCode == null
                ? ProbeAttemptStartState.EXISTING_IN_FLIGHT
                : ProbeAttemptStartState.EXISTING_FINALIZED;
    }

    /**
     * 완료·중단 대상이 DB 권위 식별자를 가졌는지 확인한다.
     *
     * @param id 저장된 Probe attempt 식별자
     */
    private void requirePersistedId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("저장된 Probe attempt id가 필요합니다.");
        }
    }
}
