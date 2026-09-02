package io.outcomeprobe.profile;

import java.util.List;
import java.util.Optional;

/**
 * 등록된 Target Profile을 조회하는 포트다.
 *
 * <p>책임: execution·probe·delivery 모듈이 Profile 저장 세부 구현을 모르고
 * 신뢰 경계 정보를 얻게 한다.</p>
 * <p>핵심 불변식: 반환되는 Profile에는 secret 원문이 없다. secretRef 이름만 존재한다.</p>
 */
public interface ProfileLookup {

    /**
     * 이름으로 활성 최신 Profile과 Artifact version·hash 원본을 함께 조회한다.
     *
     * <p>Run 생성 경계에서만 사용한다. Run이 저장된 뒤 실행 경로는 반드시
     * {@link TargetProfileSafeSnapshot}을 사용하고 이 포트를 다시 호출하지 않는다.</p>
     *
     * @param name Profile 이름
     * @return 존재하면 versioned Profile, 없으면 empty
     */
    Optional<VersionedTargetProfile> findVersionedByName(String name);

    /**
     * 이름으로 활성 Profile을 조회한다.
     *
     * @param name Profile 이름
     * @return 존재하면 Profile, 없으면 empty
     */
    default Optional<TargetProfile> findByName(String name) {
        return findVersionedByName(name).map(VersionedTargetProfile::profile);
    }

    /**
     * 모든 활성 Profile을 조회한다.
     *
     * @return Profile 목록
     */
    List<TargetProfile> findAll();
}
