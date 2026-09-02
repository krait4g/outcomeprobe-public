package io.outcomeprobe.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.outcomeprobe.profile.VersionedTargetProfile;
import io.outcomeprobe.profile.TargetProfileSpecMapper;
import io.outcomeprobe.profile.adapter.out.persistence.JdbcProfileLookup;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactMetadata;
import io.outcomeprobe.scenario.ArtifactRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** SemVer Profile 선택의 수치·pre-release·build tie 규칙을 검증한다. */
class SemanticVersionComparatorTest {

    /** 1.10.0은 문자열 순서와 무관하게 1.9.0보다 커야 한다. */
    @Test
    void comparesNumericCoreAndPrereleaseBySemver() {
        assertThat(SemanticVersionComparator.INSTANCE.compare("1.10.0", "1.9.0"))
                .isPositive();
        assertThat(SemanticVersionComparator.INSTANCE.compare("2.0.0", "2.0.0-rc.1"))
                .isPositive();
        assertThat(SemanticVersionComparator.INSTANCE.compare("2.0.0-rc.10", "2.0.0-rc.2"))
                .isPositive();
        assertThat(SemanticVersionComparator.INSTANCE.compare("1.0.0+aaa", "1.0.0+bbb"))
                .isZero();
    }

    /** 같은 precedence의 build metadata는 원문 version으로 결정론적 tie-break한다. */
    @Test
    void profileLookupUsesStableBuildMetadataTieBreak() {
        ArtifactRepository repository = org.mockito.Mockito.mock(ArtifactRepository.class);
        org.mockito.Mockito.when(repository.findAllActive(ArtifactKind.TARGET_PROFILE))
                .thenReturn(List.of(profile("1.0.0+aaa"), profile("1.0.0+bbb")));
        JdbcProfileLookup lookup = new JdbcProfileLookup(repository, new TargetProfileSpecMapper());

        VersionedTargetProfile selected = lookup.findVersionedByName("semver-target").orElseThrow();

        assertThat(selected.version()).isEqualTo("1.0.0+bbb");
    }

    /**
     * Profile Artifact fixture를 만든다.
     *
     * @param version SemVer
     * @return Profile Artifact
     */
    private ArtifactDefinition profile(String version) {
        ObjectMapper mapper = new ObjectMapper();
        String raw = "profile-" + version;
        return new ArtifactDefinition(version, ArtifactKind.TARGET_PROFILE,
                new ArtifactMetadata("semver-target", "SemVer Target", version, Map.of()),
                "outcomeprobe.io/v1alpha1", HashValue.sha256(raw), raw,
                mapper.createObjectNode()
                        .put("baseUri", "http://localhost:8091")
                        .set("allowedPaths", mapper.createArrayNode().add("/**")),
                false, Instant.EPOCH, null);
    }
}
