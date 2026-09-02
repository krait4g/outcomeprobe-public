package io.outcomeprobe.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Target Profile 실제 YAML fixture가 조회와 snapshot에서 공유하는 모델로 매핑되는지 검증한다.
 *
 * <p>검출 가능한 결함: 정식 네트워크 필드보다 legacy 필드를 우선하는 회귀, legacy
 * Artifact 호환 중단, secret 원문을 복사하거나 실행 필드를 누락하는 회귀.</p>
 */
class TargetProfileSpecMapperTest {

    private static final String OFFICIAL_PROFILE_YAML = """
            apiVersion: outcomeprobe.io/v1alpha1
            kind: TargetProfile
            metadata:
              name: mapper-target
              displayName: Mapper Target
              version: 1.2.3
            spec:
              baseUri: http://official.example.test:8443
              allowedPaths:
                - /api/**
                - /health
              allowedMethods:
                - GET
                - POST
              allowedHosts:
                - official.example.test
              allowedPorts:
                - 8443
              networkPolicy:
                allowedHosts:
                  - legacy.example.test
                allowedPorts:
                  - 18080
              defaultHeaders:
                Accept: application/json
                X-Test-Scope: isolated
              secretHeaders:
                Authorization:
                  secretRef: target-auth-token
              callbackPaths:
                order-created: /callbacks/orders
              redaction:
                headers:
                  - Authorization
                jsonPaths:
                  - $.customer.email
            """;

    private static final String LEGACY_PROFILE_YAML = """
            apiVersion: outcomeprobe.io/v1alpha1
            kind: TargetProfile
            metadata:
              name: legacy-target
              displayName: Legacy Network Policy Target
              version: 0.9.0
            spec:
              baseUri: http://legacy.example.test:18080
              allowedPaths:
                - /**
              allowedMethods:
                - GET
              networkPolicy:
                allowedHosts:
                  - legacy.example.test
                allowedPorts:
                  - 18080
            """;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final TargetProfileSpecMapper mapper = new TargetProfileSpecMapper();

    /** 정식 allowedHosts·allowedPorts가 있으면 legacy 값은 실행 모델에 섞이지 않아야 한다. */
    @Test
    void officialNetworkFieldsTakePriorityOverLegacyFields() throws Exception {
        JsonNode root = yamlMapper.readTree(OFFICIAL_PROFILE_YAML);

        TargetProfile profile = mapper.toProfile(
                root.path("metadata").path("name").asText(), root.path("spec"));

        assertThat(profile.allowedHosts()).containsExactly("official.example.test");
        assertThat(profile.allowedPorts()).containsExactly(8443);
    }

    /** 정식 네트워크 필드가 없는 기존 Artifact는 networkPolicy 값을 계속 사용해야 한다. */
    @Test
    void legacyNetworkPolicyRemainsSupportedAsFallback() throws Exception {
        JsonNode root = yamlMapper.readTree(LEGACY_PROFILE_YAML);

        TargetProfile profile = mapper.toProfile(
                root.path("metadata").path("name").asText(), root.path("spec"));

        assertThat(profile.allowedHosts()).containsExactly("legacy.example.test");
        assertThat(profile.allowedPorts()).containsExactly(18080);
    }

    /** 실행 필드 전체와 secretRef 이름만 실제 YAML 의미대로 매핑해야 한다. */
    @Test
    void mapsEveryExecutionFieldAndOnlySecretReferences() throws Exception {
        JsonNode root = yamlMapper.readTree(OFFICIAL_PROFILE_YAML);

        TargetProfile profile = mapper.toProfile(
                root.path("metadata").path("name").asText(), root.path("spec"));

        assertThat(profile.name()).isEqualTo("mapper-target");
        assertThat(profile.baseUri()).isEqualTo("http://official.example.test:8443");
        assertThat(profile.allowedPaths()).containsExactly("/api/**", "/health");
        assertThat(profile.allowedMethods()).containsExactly("GET", "POST");
        assertThat(profile.defaultHeaders()).isEqualTo(Map.of(
                "Accept", "application/json", "X-Test-Scope", "isolated"));
        assertThat(profile.secretHeaders())
                .isEqualTo(Map.of("Authorization", "target-auth-token"));
        assertThat(profile.callbackPaths())
                .isEqualTo(Map.of("order-created", "/callbacks/orders"));
        assertThat(profile.redactionHeaderNames()).containsExactly("Authorization");
        assertThat(profile.redactionJsonPaths()).containsExactly("$.customer.email");
        assertThat(profile.secretHeaders().values())
                .containsExactlyElementsOf(List.of("target-auth-token"));
    }
}
