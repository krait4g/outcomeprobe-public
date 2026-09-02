package io.outcomeprobe.profile.adapter.out.persistence;

import io.outcomeprobe.profile.ProfileLookup;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSpecMapper;
import io.outcomeprobe.profile.VersionedTargetProfile;
import io.outcomeprobe.foundation.SemanticVersionComparator;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * TARGET_PROFILE Artifact를 읽어 {@link TargetProfile}로 변환하는 adapter다.
 *
 * <p>책임: Profile을 독립 테이블이 아니라 Artifact 체계로 유지해 version·hash·
 * snapshot 계약을 다른 Artifact와 공유하게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: secretHeaders의 값은 secretRef 이름이며
 * 실제 secret 해석은 이 adapter가 하지 않는다.</p>
 */
@Component
public class JdbcProfileLookup implements ProfileLookup {

    private final ArtifactRepository artifactRepository;
    private final TargetProfileSpecMapper profileSpecMapper;

    /**
     * 조회에 필요한 협업 객체를 주입한다.
     *
     * @param artifactRepository Artifact 저장소
     * @param profileSpecMapper 저장된 spec과 snapshot 검증이 공유하는 의미 매퍼
     */
    public JdbcProfileLookup(ArtifactRepository artifactRepository,
                             TargetProfileSpecMapper profileSpecMapper) {
        this.artifactRepository = artifactRepository;
        this.profileSpecMapper = profileSpecMapper;
    }

    /**
     * 이름으로 Profile Artifact를 찾아 도메인 모델로 변환한다.
     *
     * @param name Profile 이름
     * @return 존재하면 Profile, 없으면 empty
     */
    @Override
    public Optional<VersionedTargetProfile> findVersionedByName(String name) {
        // 문자열 정렬이 아니라 SemVer 의미로 활성 최신 version을 고정한다.
        return artifactRepository.findAllActive(ArtifactKind.TARGET_PROFILE).stream()
                .filter(definition -> definition.metadata().name().equals(name))
                .max((left, right) -> {
                    int version = SemanticVersionComparator.INSTANCE.compare(
                            left.metadata().version(), right.metadata().version());
                    if (version != 0) {
                        return version;
                    }
                    int fullVersion = left.metadata().version().compareTo(right.metadata().version());
                    if (fullVersion != 0) {
                        return fullVersion;
                    }
                    return left.contentHash().hexValue().compareTo(right.contentHash().hexValue());
                })
                .map(this::toVersionedProfile);
    }

    /**
     * 모든 활성 Profile을 변환해 반환한다.
     *
     * @return Profile 목록
     */
    @Override
    public List<TargetProfile> findAll() {
        return artifactRepository.findAllActive(ArtifactKind.TARGET_PROFILE).stream()
                .map(this::toProfile)
                .toList();
    }

    /**
     * Artifact 원본 metadata와 타입 안전 Profile을 Run snapshot 후보로 결합한다.
     */
    private VersionedTargetProfile toVersionedProfile(ArtifactDefinition definition) {
        return new VersionedTargetProfile(
                definition.apiVersion(),
                definition.metadata().version(),
                definition.contentHash().hexValue(),
                definition.rawYaml(),
                definition.parsedJson(),
                toProfile(definition));
    }

    /**
     * Artifact 정의를 TargetProfile 도메인 모델로 변환한다.
     *
     * <p>allowedHosts·allowedPorts는 정식 구조 spec.allowedHosts·spec.allowedPorts를
     * 우선 읽는다. 기존 spec.networkPolicy 형식은 한 버전 동안 fallback으로 지원한다.</p>
     */
    private TargetProfile toProfile(ArtifactDefinition definition) {
        return profileSpecMapper.toProfile(
                definition.metadata().name(), definition.parsedJson());
    }
}
