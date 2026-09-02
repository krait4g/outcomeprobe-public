package io.outcomeprobe.emulator.adapter.out.persistence;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.emulator.ProviderResource;
import io.outcomeprobe.emulator.ProviderStatePort;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * provider_resource 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: Provider truth 변경을 version CAS로 원자화하고 stale view 계획을
 * 조회 시점에 적용한다.</p>
 * <p>핵심 불변식: commit CAS가 영향 행 0건을 반환하면 어떤 상태도 바뀌지 않는다.
 * 호출자는 재조회 후 재시도 또는 conflict 판단을 수행한다.</p>
 */
@Repository
public class JdbcProviderStateRepository implements ProviderStatePort {

    private static final TypeReference<Map<String, Object>> ATTRIBUTE_MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final JsonbSupport jsonb;
    private final ObjectMapper objectMapper;

    /**
     * 상태 관리에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param idGenerator 행 식별자 생성기
     * @param clockProvider 변경 시각 제공자
     * @param objectMapper 속성 JSON 변환용 mapper
     */
    public JdbcProviderStateRepository(JdbcClient jdbcClient, IdGenerator idGenerator,
                                       ClockProvider clockProvider, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.objectMapper = objectMapper;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * 새 자원을 생성한다.
     *
     * <p>같은 key 재시도는 unique index가 차단한다. seed는 Run 준비 단계에서
     * 한 번만 실행되므로 멱등 재시도는 호출자가 관리한다.</p>
     *
     * @param resource 생성할 자원 상태
     */
    @Override
    public void seed(ProviderResource resource) {
        jdbcClient.sql("""
                        INSERT INTO provider_resource (
                            id, run_id, resource_type, resource_id, canonical_state, visible_state,
                            version, remaining_stale_query_attempts, visible_after, attributes, updated_at
                        ) VALUES (
                            :id, :runId, :resourceType, :resourceId, :canonicalState, :visibleState,
                            :version, :remainingStale, :visibleAfter, :attributes, :updatedAt
                        )
                        """)
                .param("id", resource.id() == null ? idGenerator.nextId() : resource.id())
                .param("runId", resource.runId())
                .param("resourceType", resource.resourceType())
                .param("resourceId", resource.resourceId())
                .param("canonicalState", resource.canonicalState())
                .param("visibleState", resource.visibleState())
                .param("version", resource.version())
                .param("remainingStale", resource.remainingStaleQueryAttempts())
                .param("visibleAfter", resource.visibleAfter() == null ? null
                        : Timestamp.from(resource.visibleAfter()))
                .param("attributes", jsonb.ofValue(resource.attributes()))
                .param("updatedAt", Timestamp.from(clockProvider.now()))
                .update();
    }

    /**
     * 자원을 조회한다.
     *
     * @param runId Run 식별자
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @return 존재하면 자원, 없으면 empty
     */
    @Override
    public Optional<ProviderResource> find(String runId, String resourceType, String resourceId) {
        return jdbcClient.sql("""
                        SELECT id, run_id, resource_type, resource_id, canonical_state, visible_state,
                               version, remaining_stale_query_attempts, visible_after, attributes::text, updated_at
                        FROM provider_resource
                        WHERE run_id = :runId AND resource_type = :resourceType AND resource_id = :resourceId
                        """)
                .param("runId", runId)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .query(new ResourceRowMapper())
                .optional();
    }

    /**
     * 기대 version과 일치할 때만 commit 상태를 반영한다.
     *
     * <p>왜 version CAS인가: 동시 operation이 같은 자원을 둘 다 commit하면
     * Provider truth가 훼손된다. CAS 실패는 충돌 사실을 숨기지 않고 호출자에 돌려준다.</p>
     *
     * @param runId Run 식별자
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @param expectedVersion 읽은 시점의 version
     * @param newCanonicalState commit할 canonical 상태
     * @param newVisibleState 조회 노출 상태
     * @param remainingStaleQueryAttempts stale 조회 남은 횟수
     * @param visibleAfter 시간 기준 stale 만료 시각, 없으면 null
     * @param attributes 병합할 속성
     * @return CAS 성공 여부
     */
    @Override
    public boolean commitTransition(String runId, String resourceType, String resourceId,
                                    long expectedVersion, String newCanonicalState, String newVisibleState,
                                    int remainingStaleQueryAttempts, Instant visibleAfter,
                                    Map<String, Object> attributes) {
        int updated = jdbcClient.sql("""
                        UPDATE provider_resource
                        SET canonical_state = :canonicalState,
                            visible_state = :visibleState,
                            version = version + 1,
                            remaining_stale_query_attempts = :remainingStale,
                            visible_after = :visibleAfter,
                            attributes = attributes || CAST(:attributes AS jsonb),
                            updated_at = :updatedAt
                        WHERE run_id = :runId
                          AND resource_type = :resourceType
                          AND resource_id = :resourceId
                          AND version = :expectedVersion
                        """)
                .param("canonicalState", newCanonicalState)
                .param("visibleState", newVisibleState)
                .param("remainingStale", remainingStaleQueryAttempts)
                .param("visibleAfter", visibleAfter == null ? null : Timestamp.from(visibleAfter))
                .param("attributes", jsonb.write(attributes))
                .param("updatedAt", Timestamp.from(clockProvider.now()))
                .param("runId", runId)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated > 0;
    }

    /**
     * 조회 요청을 반영해 stale view를 소모한다.
     *
     * <p>시간 기준(visibleAfter)과 횟수 기준(staleAttempts) 두 stale을 지원한다.
     * visibleAfter 이전에는 오래된 상태를 그대로 반환하고, 시각이 지나면
     * visibleState가 canonicalState로 수렴한다. 횟수 기준은 조회마다 하나씩 소모한다.</p>
     *
     * @param runId Run 식별자
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @return 조회 후 자원 상태, 없으면 empty
     */
    @Override
    public Optional<ProviderResource> consumeQuery(String runId, String resourceType, String resourceId) {
        Optional<ProviderResource> current = find(runId, resourceType, resourceId);
        if (current.isEmpty()) {
            return current;
        }
        ProviderResource resource = current.get();

        // 시간 기준 stale: visibleAfter 이전에는 오래된 상태를 그대로 반환한다.
        if (resource.visibleAfter() != null) {
            if (clockProvider.now().isBefore(resource.visibleAfter())) {
                return current;
            }
            // 시각이 지났으면 visible 상태를 truth로 수렴시키고 시간 stale을 해제한다.
            convergeVisibleState(runId, resource);
            return find(runId, resourceType, resourceId);
        }

        // 횟수 기준 stale: 남은 횟수를 하나 소모하고, 0이 되면 truth로 수렴한다.
        if (!resource.hasStaleView()) {
            return current;
        }
        int remaining = resource.remainingStaleQueryAttempts() - 1;
        String visible = remaining > 0 ? resource.visibleState() : resource.canonicalState();
        jdbcClient.sql("""
                        UPDATE provider_resource
                        SET remaining_stale_query_attempts = :remaining,
                            visible_state = :visible,
                            updated_at = :updatedAt
                        WHERE run_id = :runId
                          AND resource_type = :resourceType
                          AND resource_id = :resourceId
                          AND version = :expectedVersion
                        """)
                .param("remaining", remaining)
                .param("visible", visible)
                .param("updatedAt", Timestamp.from(clockProvider.now()))
                .param("runId", runId)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("expectedVersion", resource.version())
                .update();
        return find(runId, resourceType, resourceId);
    }

    /**
     * 시간 stale이 만료된 자원의 visible 상태를 canonical으로 수렴시킨다.
     */
    private void convergeVisibleState(String runId, ProviderResource resource) {
        jdbcClient.sql("""
                        UPDATE provider_resource
                        SET visible_state = canonical_state,
                            visible_after = NULL,
                            updated_at = :updatedAt
                        WHERE run_id = :runId
                          AND resource_type = :resourceType
                          AND resource_id = :resourceId
                          AND version = :expectedVersion
                        """)
                .param("updatedAt", Timestamp.from(clockProvider.now()))
                .param("runId", runId)
                .param("resourceType", resource.resourceType())
                .param("resourceId", resource.resourceId())
                .param("expectedVersion", resource.version())
                .update();
    }

    /**
     * provider_resource 행을 도메인 record로 변환한다.
     *
     * <p>책임: JSONB 속성과 SQL 타입을 도메인 타입으로 경계 짓는다.</p>
     */
    private final class ResourceRowMapper implements RowMapper<ProviderResource> {

        /**
         * 한 행을 ProviderResource로 변환한다.
         *
         * @param rs 결과셋
         * @param rowNum 행 번호
         * @return 변환된 자원
         * @throws SQLException 컬럼 접근 실패 시 발생한다
         */
        @Override
        public ProviderResource mapRow(ResultSet rs, int rowNum) throws SQLException {
            Map<String, Object> attributes;
            try {
                attributes = objectMapper.readValue(rs.getString("attributes"), ATTRIBUTE_MAP);
            } catch (Exception e) {
                throw new IllegalStateException("resource 속성 해석에 실패했습니다.", e);
            }
            Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
            Timestamp visibleAfterTs = rs.getTimestamp("visible_after");
            return new ProviderResource(
                    rs.getString("id"),
                    rs.getString("run_id"),
                    rs.getString("resource_type"),
                    rs.getString("resource_id"),
                    rs.getString("canonical_state"),
                    rs.getString("visible_state"),
                    rs.getLong("version"),
                    rs.getInt("remaining_stale_query_attempts"),
                    visibleAfterTs == null ? null : visibleAfterTs.toInstant(),
                    attributes,
                    updatedAt);
        }
    }
}
