package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.VersionedTargetProfile;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactImportService;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactMetadata;
import io.outcomeprobe.scenario.ArtifactParser;
import io.outcomeprobe.scenario.ArtifactEnvelope;
import io.outcomeprobe.scenario.ScenarioMapper;
import io.outcomeprobe.scenario.SchemaValidationService;
import io.outcomeprobe.scenario.adapter.out.persistence.JdbcArtifactRepository;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 민감 Profile header가 import와 Run snapshot 두 경계에서 거부되는지 검증한다. */
class ArtifactSnapshotSecurityTest {

    private static final String API_VERSION = "outcomeprobe.io/v1alpha1";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Authorization literal은 import 검증에서 secretRef 안내와 함께 거부돼야 한다. */
    @Test
    void sensitiveDefaultHeaderIsRejectedAtImportBoundary() {
        ArtifactImportService service = importService();
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: TargetProfile
                metadata: { name: unsafe-target, displayName: Unsafe, version: 1.0.0 }
                spec:
                  baseUri: http://localhost:8091
                  allowedPaths: [/**]
                  allowedMethods: [GET, POST]
                  defaultHeaders:
                    Authorization: Bearer literal-secret
                """;

        assertThatThrownBy(() -> service.validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretHeaders.secretRef");
    }

    /** baseUri user-info로 평문 credential을 숨겨 Profile snapshot에 넣을 수 없어야 한다. */
    @Test
    void baseUriUserInfoCredentialIsRejectedWithoutLeak() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: TargetProfile
                metadata: { name: unsafe-uri, displayName: Unsafe, version: 1.0.0 }
                spec:
                  baseUri: http://alice:plain-password@localhost:8091
                  allowedPaths: [/**]
                  allowedMethods: [GET, POST]
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("baseUri")
                .hasMessageNotContaining("plain-password");
    }

    /** Scenario target.http의 Authorization literal도 재귀 import 정책으로 거부돼야 한다. */
    @Test
    void sensitiveScenarioRequestHeaderIsRejectedAtImportBoundary() {
        String yamlTemplate = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata: { name: unsafe-scenario, displayName: Unsafe, version: 1.0.0 }
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  steps:
                    - id: call-target
                      type: target.http
                      request:
                        method: GET
                        path: /api/resource
                        headers: { HEADER_NAME: "literal-value" }
                """;

        for (String headerName : List.of(
                "Authorization", "X-Auth-Token", "X-Client-Secret", "X-DB-Password")) {
            String yaml = yamlTemplate.replace("HEADER_NAME", headerName);
            assertThatThrownBy(() -> importService().validate(yaml))
                    .as("민감 header %s", headerName)
                    .isInstanceOf(ArtifactImportException.class)
                    .hasMessageContaining("secret");
        }
    }

    /** 민감 query credential은 Scenario snapshot과 Bundle에 들어가기 전에 거부돼야 한다. */
    @Test
    void sensitiveQueryCredentialIsRejectedAtImportBoundary() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata: { name: unsafe-query, displayName: Unsafe, version: 1.0.0 }
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  steps:
                    - id: probe-target
                      type: probe.eventually
                      within: 1s
                      interval: 100ms
                      request:
                        method: GET
                        path: /state?access_token=literal-query-secret
                      assertions:
                        - { jsonPath: $.status, equals: SUCCEEDED }
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("query credential")
                .hasMessageNotContaining("literal-query-secret");
    }

    /** Contract의 중첩 headers에 둔 Cookie literal도 mapper 변환 전에 거부돼야 한다. */
    @Test
    void sensitiveContractHeaderIsRejectedAtImportBoundary() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: ProviderContract
                metadata: { name: unsafe-contract, displayName: Unsafe, version: 1.0.0 }
                spec:
                  emulatorAlias: unsafe-alias
                  resourceTypes:
                    thing: { initialStates: [A], terminalStates: [B], allowedTransitions: { A: [B] } }
                  operations:
                    getThing:
                      request: { method: GET, path: "/things/{id}", resourceType: thing, resourceId: { source: PATH, expression: id } }
                      successResponse:
                        status: 200
                        headers: { Cookie: "session=literal-value" }
                        body: { id: "${resource.id}" }
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretHeaders.secretRef");
    }

    /** 올바른 secretHeaders.secretRef 구조는 credential 원문이 없으므로 허용해야 한다. */
    @Test
    void secretHeaderReferenceIsAllowed() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: TargetProfile
                metadata: { name: safe-target, displayName: Safe, version: 1.0.0 }
                spec:
                  baseUri: http://localhost:8091
                  allowedPaths: [/**]
                  allowedMethods: [GET, POST]
                  secretHeaders:
                    Authorization: { secretRef: target-auth-token }
                """;

        assertThatCode(() -> importService().validate(yaml)).doesNotThrowAnyException();
    }

    /** Scenario 변수의 password literal도 compiled plan과 Bundle에 들어가기 전에 거부해야 한다. */
    @Test
    void sensitiveVariableLiteralIsRejectedAtImportBoundary() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata: { name: unsafe-variable, displayName: Unsafe, version: 1.0.0 }
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  variables:
                    password: literal-production-password
                  steps:
                    - id: call-target
                      type: target.http
                      request: { method: GET, path: /api/resource }
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretRef")
                .hasMessageNotContaining("literal-production-password");
    }

    /** metadata label의 credential literal도 문서 전체 import 경계에서 거부해야 한다. */
    @Test
    void sensitiveMetadataLabelIsRejectedAtImportBoundary() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata:
                  name: unsafe-label
                  displayName: Unsafe
                  version: 1.0.0
                  labels: { password: literal-production-password }
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  steps:
                    - id: call-target
                      type: target.http
                      request: { method: GET, path: /api/resource }
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretRef")
                .hasMessageNotContaining("literal-production-password");
    }

    /** 복합 credential field 이름도 safeSpec 우회로 사용할 수 없어야 한다. */
    @Test
    void compoundCredentialFieldNamesAreRejected() {
        String yamlTemplate = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata: { name: unsafe-compound, displayName: Unsafe, version: 1.0.0 }
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  variables: { FIELD_NAME: literal-production-credential }
                  steps:
                    - id: call-target
                      type: target.http
                      request: { method: GET, path: /api/resource }
                """;

        for (String fieldName : List.of(
                "awsAccessKey", "databaseCredential", "signingPrivateKey", "bearerValue")) {
            assertThatThrownBy(() -> importService().validate(
                    yamlTemplate.replace("FIELD_NAME", fieldName)))
                    .as("민감 복합 field %s", fieldName)
                    .isInstanceOf(ArtifactImportException.class)
                    .hasMessageNotContaining("literal-production-credential");
        }
    }

    /** 원문 주석은 source hash에만 반영되고 Run의 safeRawYaml에서는 제거돼야 한다. */
    @Test
    void snapshotRemovesCommentsThatCouldContainCredentials() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata: { name: safe-comment, displayName: Safe, version: 1.0.0 }
                # operator-note: literal-comment-credential
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  steps:
                    - id: call-target
                      type: target.http
                      request: { method: GET, path: /api/resource }
                """;
        ArtifactEnvelope envelope = importService().validate(yaml);
        ArtifactDefinition definition = new ArtifactDefinition(
                "safe-comment-id", envelope.kind(), envelope.metadata(), envelope.apiVersion(),
                envelope.contentHash(), envelope.rawYaml(), envelope.spec(), false, Instant.EPOCH, null);

        ArtifactSnapshot snapshot = new RunSnapshotIntegrity(objectMapper)
                .snapshot(definition, ArtifactKind.SCENARIO);

        assertThat(snapshot.sourceContentHash()).isEqualTo(HashValue.sha256(yaml).hexValue());
        assertThat(snapshot.safeContentHash())
                .isEqualTo(HashValue.sha256(snapshot.safeRawYaml()).hexValue());
        assertThat(snapshot.safeRawYaml())
                .contains("safe-comment")
                .doesNotContain("literal-comment-credential")
                .doesNotContain("# operator-note");
    }

    /** credential assignment를 적은 YAML comment는 raw Artifact 저장 전에 거부해야 한다. */
    @Test
    void credentialAssignmentInCommentIsRejectedWithoutLeak() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata: { name: unsafe-comment, displayName: Unsafe, version: 1.0.0 }
                # password: literal-comment-password
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  steps:
                    - id: call-target
                      type: target.http
                      request: { method: GET, path: /api/resource }
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("comment")
                .hasMessageNotContaining("literal-comment-password");
    }

    /** YAML duplicate key로 검증된 값 뒤에 credential을 숨길 수 없어야 한다. */
    @Test
    void duplicateYamlKeysAreRejected() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: Scenario
                metadata: { name: duplicate-key, displayName: Duplicate, version: 1.0.0 }
                spec:
                  contractRef: { name: contract-one, version: 1.0.0 }
                  variables:
                    safeValue: first
                    safeValue: second
                  steps:
                    - id: call-target
                      type: target.http
                      request: { method: GET, path: /api/resource }
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("YAML 해석에 실패");
    }

    /** 기존 DB의 password literal도 safe snapshot 생성 경계에서 다시 거부해야 한다. */
    @Test
    void sensitiveVariableLiteralIsRejectedAtSnapshotBoundary() {
        String raw = "unsafe-variable-scenario-yaml";
        var spec = objectMapper.createObjectNode();
        spec.putObject("contractRef").put("name", "contract-one").put("version", "1.0.0");
        spec.putObject("variables").put("password", "literal-production-password");
        spec.putArray("steps").addObject().put("id", "call-target").put("type", "target.http");
        ArtifactDefinition definition = new ArtifactDefinition(
                "unsafe-variable-id", ArtifactKind.SCENARIO,
                new ArtifactMetadata("unsafe-variable", "Unsafe", "1.0.0", Map.of()),
                API_VERSION, HashValue.sha256(raw), raw, spec, false, Instant.EPOCH, null);

        assertThatThrownBy(() -> new RunSnapshotIntegrity(objectMapper)
                .snapshot(definition, ArtifactKind.SCENARIO))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("credential literal")
                .hasMessageNotContaining("literal-production-password");
    }

    /** Profile secretRef는 환경변수와 일대일인 소문자 kebab-case만 허용해야 한다. */
    @Test
    void nonCanonicalSecretReferenceIsRejectedAtImportBoundary() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: TargetProfile
                metadata: { name: unsafe-ref, displayName: Unsafe, version: 1.0.0 }
                spec:
                  baseUri: http://localhost:8091
                  allowedPaths: [/**]
                  allowedMethods: [GET, POST]
                  secretHeaders:
                    Authorization: { secretRef: TARGET_API_TOKEN }
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretRef")
                .hasMessageNotContaining("TARGET_API_TOKEN");
    }

    /** Callback signature의 일반 secretRef도 canonical 참조 이름만 허용해야 한다. */
    @Test
    void nonCanonicalCallbackSignatureSecretReferenceIsRejected() {
        String yaml = """
                apiVersion: outcomeprobe.io/v1alpha1
                kind: ProviderContract
                metadata: { name: unsafe-callback-ref, displayName: Unsafe, version: 1.0.0 }
                spec:
                  emulatorAlias: unsafealias
                  resourceTypes:
                    thing: { initialStates: [A], terminalStates: [B], allowedTransitions: { A: [B] } }
                  operations:
                    getThing:
                      request: { method: GET, path: "/things/{id}", resourceType: thing, resourceId: { source: PATH, expression: id } }
                      successResponse: { status: 200, body: { id: "${resource.id}" } }
                  callbacks:
                    thingChanged:
                      eventType: THING_CHANGED
                      resourceType: thing
                      body: { eventId: "${generated.eventId}" }
                      signature:
                        algorithm: HMAC_SHA256
                        header: X-OutcomeProbe-Signature
                        secretRef: CALLBACK_SIGNING_KEY
                """;

        assertThatThrownBy(() -> importService().validate(yaml))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("kebab-case");
    }

    /** 기존 DB에 남은 위험 Profile도 snapshot 생성 직전에 다시 거부돼야 한다. */
    @Test
    void sensitiveDefaultHeaderIsRejectedAtSnapshotBoundary() {
        String raw = "unsafe-profile-yaml";
        TargetProfile unsafe = new TargetProfile("unsafe-target", "http://localhost:8091",
                List.of("/**"), List.of("GET"), List.of("localhost"), List.of(8091),
                Map.of("x-API-key", "literal-secret"), Map.of(), Map.of(), List.of(), List.of());
        VersionedTargetProfile definition = new VersionedTargetProfile(API_VERSION, "1.0.0",
                HashValue.sha256(raw).hexValue(), raw, objectMapper.createObjectNode(), unsafe);

        assertThatThrownBy(() -> new RunSnapshotIntegrity(objectMapper).snapshot(definition))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretHeaders.secretRef");
    }

    /** 기존 DB Scenario의 민감 request header도 Run snapshot 생성 직전에 거부돼야 한다. */
    @Test
    void sensitiveScenarioHeaderIsRejectedAtSnapshotBoundary() {
        String raw = "unsafe-scenario-yaml";
        var spec = objectMapper.createObjectNode();
        spec.putObject("contractRef").put("name", "contract-one").put("version", "1.0.0");
        spec.putArray("steps").addObject().put("id", "call-target").put("type", "target.http")
                .putObject("request").putObject("headers").put("X-Api-Key", "literal-value");
        ArtifactDefinition definition = new ArtifactDefinition(
                "unsafe-scenario-id", ArtifactKind.SCENARIO,
                new ArtifactMetadata("unsafe-scenario", "Unsafe", "1.0.0", Map.of()),
                API_VERSION, HashValue.sha256(raw), raw, spec, false, Instant.EPOCH, null);

        assertThatThrownBy(() -> new RunSnapshotIntegrity(objectMapper)
                .snapshot(definition, ArtifactKind.SCENARIO))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretHeaders.secretRef");
    }

    /** secretRef 객체에 literal value를 섞은 기존 DB Profile은 snapshot 전에 거부해야 한다. */
    @Test
    void secretHeaderReferenceWithExtraLiteralIsRejectedAtSnapshotBoundary() {
        String raw = "mixed-secret-profile-yaml";
        var parsedSpec = objectMapper.createObjectNode().put("baseUri", "http://localhost:8091");
        parsedSpec.putObject("secretHeaders").putObject("Authorization")
                .put("secretRef", "target-auth-token")
                .put("value", "literal-value");
        TargetProfile profile = new TargetProfile("mixed-secret-target", "http://localhost:8091",
                List.of("/**"), List.of("GET"), List.of("localhost"), List.of(8091),
                Map.of(), Map.of("Authorization", "target-auth-token"), Map.of(), List.of(), List.of());
        VersionedTargetProfile definition = new VersionedTargetProfile(API_VERSION, "1.0.0",
                HashValue.sha256(raw).hexValue(), raw, parsedSpec, profile);

        assertThatThrownBy(() -> new RunSnapshotIntegrity(objectMapper).snapshot(definition))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("secretRef 이름만");
    }

    /** 테스트별 동일한 import 검증 서비스 조합을 만든다. */
    private ArtifactImportService importService() {
        return new ArtifactImportService(
                new ArtifactParser(new SchemaValidationService()),
                new ScenarioMapper(objectMapper), mock(JdbcArtifactRepository.class));
    }
}
