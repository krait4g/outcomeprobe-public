package io.outcomeprobe.scenario;

import io.outcomeprobe.foundation.HashValue;
import java.util.List;
import java.util.Optional;

/**
 * Artifact 저장소를 접근하는 포트다.
 *
 * <p>책임: Scenario·Contract·Profile의 영속화와 조회를 도메인 언어로 추상화해
 * execution·profile 모듈이 persistence 세부 구현을 모르고 Artifact를 사용하게 한다.</p>
 * <p>핵심 불변식: save는 동일 identity+hash에서 멱등이고, 동일 identity에 다른
 * hash 저장은 거부된다.</p>
 */
public interface ArtifactRepository {

    /**
     * Artifact를 저장한다.
     *
     * @param definition 저장할 Artifact
     */
    void save(ArtifactDefinition definition);

    /**
     * kind+name+version으로 활성 Artifact를 조회한다.
     *
     * @param kind Artifact 종류
     * @param name Artifact 이름
     * @param version SemVer 버전
     * @return 존재하면 Artifact, 없으면 empty
     */
    Optional<ArtifactDefinition> findByIdentity(ArtifactKind kind, String name, String version);

    /**
     * content hash로 Artifact를 조회한다.
     *
     * @param contentHash 원본 해시
     * @return 존재하면 Artifact, 없으면 empty
     */
    Optional<ArtifactDefinition> findByContentHash(HashValue contentHash);

    /**
     * 특정 종류의 활성 Artifact를 전부 조회한다.
     *
     * @param kind Artifact 종류
     * @return 활성 Artifact 목록
     */
    List<ArtifactDefinition> findAllActive(ArtifactKind kind);

    /**
     * Artifact를 soft delete한다.
     *
     * <p>과거 Run snapshot은 영향을 받지 않으며 새 Run 생성만 막힌다.</p>
     *
     * @param kind Artifact 종류
     * @param name Artifact 이름
     * @param version SemVer 버전
     * @return 삭제 대상이 존재했으면 true
     */
    boolean softDelete(ArtifactKind kind, String name, String version);
}
