package io.outcomeprobe.scenario.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.infrastructure.persistence.JsonbSupport;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactMetadata;
import io.outcomeprobe.scenario.ArtifactRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * artifact_definition 테이블을 다루는 persistence adapter다.
 *
 * <p>책임: Artifact identity·content hash 고유 제약과 soft delete 규칙을
 * SQL 수준에서 일관되게 적용한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 adapter는 import 정책(충돌 판단)을 결정하지
 * 않는다. 정책은 application 서비스에서 결정되고 여기서는 저장만 수행한다.</p>
 */
@Repository
public class JdbcArtifactRepository implements ArtifactRepository {

    private final JdbcClient jdbcClient;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final JsonbSupport jsonb;

    /**
     * 저장에 필요한 협업 객체를 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param idGenerator 식별자 생성기
     * @param clockProvider import 시각 제공자
     * @param objectMapper JSONB 변환용 mapper
     */
    public JdbcArtifactRepository(JdbcClient jdbcClient, IdGenerator idGenerator,
                                  ClockProvider clockProvider, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.jsonb = new JsonbSupport(objectMapper);
    }

    /**
     * Artifact 행을 insert한다.
     *
     * <p>동일 identity 재시도는 application 서비스가 사전에 걸러내므로 여기서는
     * 단순 insert만 수행한다.</p>
     *
     * @param definition 저장할 Artifact
     */
    @Override
    public void save(ArtifactDefinition definition) {
        jdbcClient.sql("""
                        INSERT INTO artifact_definition (
                            id, kind, name, version, api_version, display_name, content_hash,
                            raw_yaml, parsed_json, built_in, imported_at, deleted_at
                        ) VALUES (
                            :id, :kind, :name, :version, :apiVersion, :displayName, :contentHash,
                            :rawYaml, :parsedJson, :builtIn, :importedAt, NULL
                        )
                        """)
                .param("id", definition.id())
                .param("kind", definition.kind().name())
                .param("name", definition.metadata().name())
                .param("version", definition.metadata().version())
                .param("apiVersion", definition.apiVersion())
                .param("displayName", definition.metadata().displayName())
                .param("contentHash", definition.contentHash().hexValue())
                .param("rawYaml", definition.rawYaml())
                .param("parsedJson", jsonb.ofJson(definition.parsedJson().toString()))
                .param("builtIn", definition.builtIn())
                .param("importedAt", Timestamp.from(definition.importedAt()))
                .update();
    }

    /**
     * kind+name+version으로 활성 Artifact를 조회한다.
     *
     * @param kind Artifact 종류
     * @param name Artifact 이름
     * @param version SemVer 버전
     * @return 존재하면 Artifact, 없으면 empty
     */
    @Override
    public Optional<ArtifactDefinition> findByIdentity(ArtifactKind kind, String name, String version) {
        return jdbcClient.sql("""
                        SELECT id, kind, name, version, api_version, display_name, content_hash,
                               raw_yaml, parsed_json::text, built_in, imported_at, deleted_at
                        FROM artifact_definition
                        WHERE kind = :kind AND name = :name AND version = :version AND deleted_at IS NULL
                        """)
                .param("kind", kind.name())
                .param("name", name)
                .param("version", version)
                .query(new ArtifactRowMapper())
                .optional();
    }

    /**
     * content hash로 Artifact를 조회한다.
     *
     * @param contentHash 원본 해시
     * @return 존재하면 Artifact, 없으면 empty
     */
    @Override
    public Optional<ArtifactDefinition> findByContentHash(HashValue contentHash) {
        return jdbcClient.sql("""
                        SELECT id, kind, name, version, api_version, display_name, content_hash,
                               raw_yaml, parsed_json::text, built_in, imported_at, deleted_at
                        FROM artifact_definition
                        WHERE content_hash = :contentHash AND deleted_at IS NULL
                        """)
                .param("contentHash", contentHash.hexValue())
                .query(new ArtifactRowMapper())
                .optional();
    }

    /**
     * 특정 종류의 활성 Artifact를 이름·버전 순으로 조회한다.
     *
     * @param kind Artifact 종류
     * @return 활성 Artifact 목록
     */
    @Override
    public List<ArtifactDefinition> findAllActive(ArtifactKind kind) {
        return jdbcClient.sql("""
                        SELECT id, kind, name, version, api_version, display_name, content_hash,
                               raw_yaml, parsed_json::text, built_in, imported_at, deleted_at
                        FROM artifact_definition
                        WHERE kind = :kind AND deleted_at IS NULL
                        ORDER BY name, version
                        """)
                .param("kind", kind.name())
                .query(new ArtifactRowMapper())
                .list();
    }

    /**
     * deleted_at을 현재 시각으로 표시해 soft delete한다.
     *
     * @param kind Artifact 종류
     * @param name Artifact 이름
     * @param version SemVer 버전
     * @return 삭제 대상이 존재했으면 true
     */
    @Override
    public boolean softDelete(ArtifactKind kind, String name, String version) {
        int updated = jdbcClient.sql("""
                        UPDATE artifact_definition
                        SET deleted_at = :deletedAt
                        WHERE kind = :kind AND name = :name AND version = :version AND deleted_at IS NULL
                        """)
                .param("deletedAt", Timestamp.from(clockProvider.now()))
                .param("kind", kind.name())
                .param("name", name)
                .param("version", version)
                .update();
        return updated > 0;
    }

    /**
     * 새 Artifact 정의를 조립하는 팩토리 역할을 한다.
     *
     * <p>왜 별도 메서드인가: import 서비스가 id·시각 생성을 이 adapter에 위임하면
     * 도메인 코드가 ID 생성 시점을 알 필요가 없다.</p>
     *
     * @param envelope 해석된 envelope
     * @param builtIn 내장 여부
     * @return 식별자와 import 시각이 채워진 정의
     */
    public ArtifactDefinition newDefinition(io.outcomeprobe.scenario.ArtifactEnvelope envelope, boolean builtIn) {
        return new ArtifactDefinition(
                idGenerator.nextId(),
                envelope.kind(),
                envelope.metadata(),
                envelope.apiVersion(),
                envelope.contentHash(),
                envelope.rawYaml(),
                envelope.spec(),
                builtIn,
                clockProvider.now(),
                null);
    }

    /**
     * artifact_definition 행을 도메인 record로 변환한다.
     *
     * <p>책임: SQL 타입과 도메인 타입의 경계를 관리한다.</p>
     */
    private final class ArtifactRowMapper implements RowMapper<ArtifactDefinition> {

        /**
         * 한 행을 ArtifactDefinition로 변환한다.
         *
         * @param rs 결과셋
         * @param rowNum 행 번호
         * @return 변환된 Artifact 정의
         * @throws SQLException 컬럼 접근 실패 시 발생한다
         */
        @Override
        public ArtifactDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp deletedAt = rs.getTimestamp("deleted_at");
            return new ArtifactDefinition(
                    rs.getString("id"),
                    ArtifactKind.valueOf(rs.getString("kind")),
                    new ArtifactMetadata(
                            rs.getString("name"),
                            rs.getString("display_name"),
                            rs.getString("version"),
                            Map.of()),
                    rs.getString("api_version"),
                    new HashValue(rs.getString("content_hash")),
                    rs.getString("raw_yaml"),
                    jsonb.toTree(rs.getString("parsed_json")),
                    rs.getBoolean("built_in"),
                    rs.getTimestamp("imported_at").toInstant(),
                    deletedAt == null ? null : deletedAt.toInstant());
        }
    }
}
