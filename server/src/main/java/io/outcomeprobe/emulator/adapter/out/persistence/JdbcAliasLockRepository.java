package io.outcomeprobe.emulator.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.emulator.AliasLockPort;
import io.outcomeprobe.emulator.EmulatorRunContext;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * emulator_alias_lock 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: 부분 unique index를 활용한 원자 독점과 활성 Run 문맥 조회를 제공한다.</p>
 * <p>핵심 불변식: 독점 획득은 INSERT 경합과 부분 unique index로 판정한다.
 * JVM-local lock을 쓰지 않아 다중 Worker·다중 인스턴스에서도 안전하다.</p>
 */
@Repository
public class JdbcAliasLockRepository implements AliasLockPort {

    private final JdbcClient jdbcClient;
    private final JsonbSupport jsonb;
    private final ObjectMapper objectMapper;
    private final ClockProvider clockProvider;

    /**
     * 독점 관리에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param objectMapper 컨텍스트 JSON 변환용 mapper
     * @param clockProvider 독점 시각 제공자
     */
    public JdbcAliasLockRepository(JdbcClient jdbcClient, ObjectMapper objectMapper,
                                   ClockProvider clockProvider) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clockProvider = clockProvider;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * alias 행을 upsert해 독점을 시도한다.
     *
     * <p>왜 두 단계인가: 이미 해제된 행이 존재하면 INSERT가 PK 충돌을 일으키므로,
     * 먼저 조건부 UPDATE로 빈 행을 점유하고 실패하면 INSERT로 새 행을 만든다.
     * 부분 unique index가 동시 INSERT 중 하나만 성공시킨다.</p>
     *
     * @param alias 독점할 alias
     * @param runId 독점하려는 Run 식별자
     * @param context Emulator가 사용할 Run 문맥 snapshot
     * @return 획득 성공 여부
     */
    @Override
    public boolean tryAcquire(String alias, String runId, EmulatorRunContext context) {
        // 해제된 행, 또는 holder Run이 terminal로 끝나 release가 누락된 행을 회수한다.
        // terminal holder 회수는 crash·누락으로 alias가 영구 고정되는 것을 막는다(OP-AUDIT-CORE-002).
        int updated = jdbcClient.sql("""
                        UPDATE emulator_alias_lock l
                        SET run_id = :runId,
                            context_json = :context,
                            acquired_at = :acquiredAt,
                            released_at = NULL,
                            version = l.version + 1
                        WHERE l.alias = :alias
                          AND (l.released_at IS NOT NULL
                               OR EXISTS (SELECT 1 FROM scenario_run r
                                          WHERE r.id = l.run_id
                                            AND r.status IN ('PASSED', 'FAILED', 'ERROR', 'CANCELLED')))
                        """)
                .param("runId", runId)
                .param("context", jsonb.ofValue(context))
                .param("acquiredAt", Timestamp.from(clockProvider.now()))
                .param("alias", alias)
                .update();
        if (updated > 0) {
            return true;
        }
        // 상위 Run 트랜잭션을 PostgreSQL 오류 상태로 만들지 않도록 충돌을 SQL 결과로 판정한다.
        int inserted = jdbcClient.sql("""
                        INSERT INTO emulator_alias_lock (alias, run_id, context_json, acquired_at, released_at, version)
                        VALUES (:alias, :runId, :context, :acquiredAt, NULL, 0)
                        ON CONFLICT DO NOTHING
                        """)
                .param("alias", alias)
                .param("runId", runId)
                .param("context", jsonb.ofValue(context))
                .param("acquiredAt", Timestamp.from(clockProvider.now()))
                .update();
        return inserted == 1;
    }

    /**
     * 소유 Run이 일치할 때만 alias를 해제한다.
     *
     * @param alias 해제할 alias
     * @param runId 소유 Run 식별자
     */
    @Override
    public void release(String alias, String runId) {
        jdbcClient.sql("""
                        UPDATE emulator_alias_lock
                        SET released_at = :releasedAt, version = version + 1
                        WHERE alias = :alias AND run_id = :runId AND released_at IS NULL
                        """)
                .param("releasedAt", Timestamp.from(clockProvider.now()))
                .param("alias", alias)
                .param("runId", runId)
                .update();
    }

    /**
     * 활성 alias에 저장된 Run 문맥을 조회한다.
     *
     * @param alias 조회할 alias
     * @return 활성 문맥, 없으면 empty
     */
    @Override
    public Optional<EmulatorRunContext> activeContext(String alias) {
        return jdbcClient.sql("""
                        SELECT context_json::text
                        FROM emulator_alias_lock
                        WHERE alias = :alias AND released_at IS NULL
                        """)
                .param("alias", alias)
                .query((ResultSet rs, int rowNum) -> rs.getString(1))
                .optional()
                .map(this::toContext);
    }

    /**
     * alias lock에 저장된 Run 문맥 JSON을 역직렬화한다.
     */
    private EmulatorRunContext toContext(String json) {
        try {
            return objectMapper.readValue(json, EmulatorRunContext.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("alias lock 컨텍스트 해석에 실패했습니다.", e);
        }
    }
}
