package io.outcomeprobe.profile.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.profile.ProfileLookup;
import io.outcomeprobe.profile.ProfileVerifier;
import io.outcomeprobe.profile.TargetProfileSpecMapper;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactEnvelope;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactImportService;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactMetadata;
import io.outcomeprobe.scenario.ArtifactRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TargetProfile 전용 endpoint가 잘못된 Artifact를 저장 전에 거부하는지 검증한다.
 *
 * <p>검출 가능한 결함: create/update가 generic import로 먼저 저장한 뒤 kind 또는 이름을
 * 확인해 다른 Artifact가 부분 성공으로 남는 회귀.</p>
 */
class TargetProfileControllerTest {

    private final ArtifactImportService importService = mock(ArtifactImportService.class);
    private final ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
    private final ProfileLookup profileLookup = mock(ProfileLookup.class);
    private final ProfileVerifier profileVerifier = mock(ProfileVerifier.class);
    private final TargetProfileSpecMapper profileSpecMapper = mock(TargetProfileSpecMapper.class);
    private final TargetProfileController controller = new TargetProfileController(
            importService, artifactRepository, profileLookup, profileVerifier, profileSpecMapper);

    /** TargetProfile create endpoint에 다른 kind가 들어오면 import를 호출하지 않아야 한다. */
    @Test
    void createRejectsWrongKindBeforeWrite() {
        String yaml = "provider-contract-yaml";
        when(importService.validate(yaml))
                .thenReturn(envelope(yaml, ArtifactKind.PROVIDER_CONTRACT, "provider"));

        assertThatThrownBy(() -> controller.create(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("TARGET_PROFILE");

        verify(importService, never()).importArtifact(yaml, false);
        verifyNoInteractions(profileSpecMapper);
    }

    /** URL 이름과 다른 TargetProfile update는 typed 변환과 import 전에 거부해야 한다. */
    @Test
    void updateRejectsNameMismatchBeforeWrite() {
        String yaml = "target-profile-yaml";
        when(importService.validate(yaml))
                .thenReturn(envelope(yaml, ArtifactKind.TARGET_PROFILE, "different-name"));

        assertThatThrownBy(() -> controller.update("expected-name", yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("다릅니다");

        verify(importService, never()).importArtifact(yaml, false);
        verifyNoInteractions(profileSpecMapper);
    }

    /** 정상 create는 generic 검증·typed 변환을 모두 끝낸 뒤에만 import해야 한다. */
    @Test
    void createPerformsTypedValidationBeforeWrite() {
        String yaml = "target-profile-yaml";
        ArtifactEnvelope envelope = envelope(yaml, ArtifactKind.TARGET_PROFILE, "target");
        when(importService.validate(yaml)).thenReturn(envelope);
        when(importService.importArtifact(yaml, false)).thenReturn(definition(envelope));

        var response = controller.create(yaml);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        InOrder order = inOrder(importService, profileSpecMapper);
        order.verify(importService).validate(yaml);
        order.verify(profileSpecMapper).toProfile("target", envelope.spec());
        order.verify(importService).importArtifact(yaml, false);
    }

    /** 테스트용 검증 envelope를 만든다. */
    private ArtifactEnvelope envelope(String yaml, ArtifactKind kind, String name) {
        JsonNode spec = new ObjectMapper().createObjectNode();
        ArtifactMetadata metadata = new ArtifactMetadata(name, name, "1.0.0", Map.of());
        return new ArtifactEnvelope("outcomeprobe.io/v1alpha1", kind, metadata,
                spec, yaml, HashValue.sha256(yaml));
    }

    /** 정상 응답 요약에 필요한 저장 결과를 만든다. */
    private ArtifactDefinition definition(ArtifactEnvelope envelope) {
        return new ArtifactDefinition("artifact-id", envelope.kind(), envelope.metadata(),
                envelope.apiVersion(), envelope.contentHash(), envelope.rawYaml(), envelope.spec(),
                false, Instant.parse("2026-08-13T00:00:00Z"), null);
    }
}
