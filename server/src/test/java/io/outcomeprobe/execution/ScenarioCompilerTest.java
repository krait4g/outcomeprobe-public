package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactEnvelope;
import io.outcomeprobe.scenario.ArtifactParser;
import io.outcomeprobe.scenario.ScenarioMapper;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.ScenarioDefinition;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ScenarioCompiler}의 결정론·template·검증 계약을 검증한다.
 *
 * <p>검출 가능한 결함: 같은 입력에서 다른 계획이 나오는 비결정론, 변수 미해결
 * 상태로 Run이 시작되는 문제, 절대 URL이 계획에 스며드는 문제.</p>
 */
class ScenarioCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArtifactParser artifactParser = new ArtifactParser(new io.outcomeprobe.scenario.SchemaValidationService());
    private final ScenarioMapper scenarioMapper = new ScenarioMapper(objectMapper);
    private final ScenarioCompiler compiler = new ScenarioCompiler();
    private final RunSnapshotIntegrity snapshotIntegrity = new RunSnapshotIntegrity(objectMapper);

    private static final String CONTRACT_YAML = """
            apiVersion: outcomeprobe.io/v1alpha1
            kind: ProviderContract
            metadata:
              name: test-payment
              displayName: Test Payment
              version: 1.0.0
            spec:
              emulatorAlias: testpay
              resourceTypes:
                payment:
                  initialStates: [READY]
                  terminalStates: [SUCCEEDED]
                  allowedTransitions:
                    READY: [SUCCEEDED]
              operations:
                confirmPayment:
                  request:
                    method: POST
                    path: /v1/payments/{paymentId}/confirm
                    resourceType: payment
                    resourceId:
                      source: PATH
                      expression: paymentId
                  transition:
                    from: [READY]
                    committed: SUCCEEDED
                  successResponse:
                    status: 200
                    body:
                      paymentId: "${resource.id}"
            """;

    private static final String SCENARIO_YAML = """
            apiVersion: outcomeprobe.io/v1alpha1
            kind: Scenario
            metadata:
              name: test-scenario
              displayName: Test Scenario
              version: 1.0.0
            spec:
              contractRef:
                name: test-payment
                version: 1.0.0
              targetProfileRef: test-target
              seed: 777
              timeout: 10s
              variables:
                paymentId: pay-77
              steps:
                - id: seed-payment
                  type: provider.seed
                  resource:
                    type: payment
                    id: "${variables.paymentId}"
                    state: READY
                    version: 0
            """;

    private static final String PROFILE_YAML = """
            apiVersion: outcomeprobe.io/v1alpha1
            kind: TargetProfile
            metadata:
              name: test-target
              displayName: Test Target
              version: 1.0.0
            spec:
              baseUri: http://localhost:8091
              allowedPaths: [/**]
              allowedMethods: [GET, POST]
              allowedHosts: [localhost]
              allowedPorts: [8091]
            """;

    private static final String PATH_SCENARIO_YAML = """
            apiVersion: outcomeprobe.io/v1alpha1
            kind: Scenario
            metadata:
              name: path-scenario
              displayName: Path Scenario
              version: 1.0.0
            spec:
              contractRef:
                name: test-payment
                version: 1.0.0
              targetProfileRef: test-target
              timeout: 10s
              variables:
                requestPath: /api/payments/pay-77
              steps:
                - id: call-target
                  type: target.http
                  request:
                    method: GET
                    path: "${variables.requestPath}"
            """;

    /**
     * 같은 Artifact·seed·변수는 항상 같은 계획을 만들어야 재현이 성립한다.
     */
    @Test
    void sameInputProducesSamePlan() {
        CompiledRunPlan first = compileScenario();
        CompiledRunPlan second = compileScenario();

        assertThat(first.steps()).hasSize(second.steps().size());
        assertThat(first.seed()).isEqualTo(second.seed());
        assertThat(first.emulatorAlias()).isEqualTo("testpay");
        assertThat(first.steps().get(0).detail().toString())
                .isEqualTo(second.steps().get(0).detail().toString());
    }

    /**
     * 변수 override는 계획의 template 치환에 반영돼야 한다.
     */
    @Test
    void variableOverrideIsResolved() {
        CompiledRunPlan plan = compileScenario(Map.of("paymentId", "pay-override"));

        JsonNode detail = plan.steps().get(0).detail();
        assertThat(detail.path("resource").path("id").asText()).isEqualTo("pay-override");
    }

    /** 민감한 이름의 Run override는 사용 여부와 무관하게 plan 저장 전에 거부돼야 한다. */
    @Test
    void sensitiveVariableOverrideIsRejectedWithoutValueLeak() {
        String secret = "must-not-enter-compiled-plan";

        assertThatThrownBy(() -> compileScenario(Map.of("accessToken", secret)))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("credential literal")
                .hasMessageNotContaining(secret);
    }

    /** 치환으로 생성된 민감 query와 fragment는 compiled plan·Evidence에 들어갈 수 없어야 한다. */
    @Test
    void resolvedSensitiveQueryAndFragmentAreRejectedWithoutValueLeak() {
        String querySecret = "must-not-leak-query";
        String fragmentSecret = "must-not-leak-fragment";

        assertThatThrownBy(() -> compilePathScenario(Map.of(
                "requestPath", "/api/payments/pay-77?access_token=" + querySecret)))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("query credential")
                .hasMessageNotContaining(querySecret);
        assertThatThrownBy(() -> compilePathScenario(Map.of(
                "requestPath", "/api/payments/pay-77#" + fragmentSecret)))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("fragment")
                .hasMessageNotContaining(fragmentSecret);
    }

    /** 치환 결과가 absolute URL이면 Profile 우회가 가능하므로 Run 생성 전에 거부해야 한다. */
    @Test
    void resolvedAbsoluteUrlIsRejected() {
        assertThatThrownBy(() -> compilePathScenario(Map.of(
                "requestPath", "https://unregistered.example/internal")))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("absolute URL");
    }

    /** 고정된 일반 query를 가진 안전한 상대 path는 기존 DSL 계약대로 허용돼야 한다. */
    @Test
    void resolvedSafeRelativePathWithQueryIsAllowed() {
        CompiledRunPlan plan = compilePathScenario(Map.of(
                "requestPath", "/api/payments/pay-77?view=summary"));

        assertThat(plan.steps().get(0).detail().path("request").path("path").asText())
                .isEqualTo("/api/payments/pay-77?view=summary");
    }

    /** Scenario 기본값과 override를 합친 변수가 100개를 넘으면 저장 전에 거부돼야 한다. */
    @Test
    void mergedVariableCountIsBounded() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            overrides.put("extra" + i, i);
        }

        assertThatThrownBy(() -> compileScenario(overrides))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("최대 100개");
    }

    /** 변수 제한의 정확한 경계값은 과잉 차단 없이 허용돼야 한다. */
    @Test
    void exactVariableLimitsAreAllowed() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("paymentId", "a".repeat(262_142));
        overrides.put("k".repeat(128), "safe");
        for (int i = 0; i < 98; i++) {
            overrides.put("boundary" + i, i);
        }

        CompiledRunPlan plan = compileScenario(overrides);

        assertThat(plan.variables()).hasSize(100)
                .containsKey("k".repeat(128));
    }

    /** 과도한 변수 key는 plan JSON 크기와 template 식별자 비용을 키우므로 거부돼야 한다. */
    @Test
    void variableKeySizeIsBoundedByUtf8Bytes() {
        assertThatThrownBy(() -> compileScenario(Map.of("가".repeat(43), "safe")))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("128바이트");
    }

    /** 개별 override 값은 JSON 직렬화 기준 256KiB를 넘을 수 없어야 한다. */
    @Test
    void variableValueSizeIsBoundedByUtf8JsonBytes() {
        assertThatThrownBy(() -> compileScenario(Map.of(
                "paymentId", "가".repeat(87_381))))
                .isInstanceOf(ArtifactImportException.class)
                .hasMessageContaining("256KiB");
    }

    /**
     * 해결되지 않은 변수 참조는 Run 시작 전에 거부돼야 실행 중 혼란이 없다.
     */
    @Test
    void unresolvedVariableIsRejected() {
        String yaml = SCENARIO_YAML.replace("${variables.paymentId}", "${variables.missing}");
        ScenarioDefinition scenario = scenarioMapper.toScenario(
                artifactParser.parse(yaml).spec());
        ProviderContract contract = contract();

        assertThatThrownBy(() -> compiler.compile("run-1", artifactSnapshot(SCENARIO_YAML),
                scenario, artifactSnapshot(CONTRACT_YAML), contract, profileSnapshot(),
                null, Map.of(), Instant.now()))
                .isInstanceOf(ArtifactImportException.class);
    }

    /**
     * 기본 변수로 Scenario를 컴파일한다.
     */
    private CompiledRunPlan compileScenario() {
        return compileScenario(Map.of());
    }

    /**
     * override 변수로 Scenario를 컴파일한다.
     */
    private CompiledRunPlan compileScenario(Map<String, Object> overrides) {
        ScenarioDefinition scenario = scenarioMapper.toScenario(
                artifactParser.parse(SCENARIO_YAML).spec());
        return compiler.compile("run-1", artifactSnapshot(SCENARIO_YAML), scenario,
                artifactSnapshot(CONTRACT_YAML), contract(), profileSnapshot(),
                null, overrides, Instant.now());
    }

    /**
     * path template이 있는 Scenario를 override 변수로 컴파일한다.
     *
     * @param overrides Run 생성 시 적용할 변수 override
     * @return 검증된 compiled plan
     */
    private CompiledRunPlan compilePathScenario(Map<String, Object> overrides) {
        ScenarioDefinition scenario = scenarioMapper.toScenario(
                artifactParser.parse(PATH_SCENARIO_YAML).spec());
        return compiler.compile("run-path", artifactSnapshot(PATH_SCENARIO_YAML), scenario,
                artifactSnapshot(CONTRACT_YAML), contract(), profileSnapshot(),
                null, overrides, Instant.now());
    }

    /**
     * 테스트용 Provider Contract를 구성한다.
     */
    private ProviderContract contract() {
        return scenarioMapper.toContract(artifactParser.parse(CONTRACT_YAML).spec());
    }

    /**
     * YAML fixture를 실제 source/canonical hash를 가진 Artifact snapshot으로 변환한다.
     *
     * @param yaml Artifact YAML
     * @return 검증된 snapshot
     */
    private ArtifactSnapshot artifactSnapshot(String yaml) {
        ArtifactEnvelope envelope = artifactParser.parse(yaml);
        ArtifactDefinition definition = new ArtifactDefinition(
                envelope.metadata().name() + "-id", envelope.kind(), envelope.metadata(),
                envelope.apiVersion(), envelope.contentHash(), envelope.rawYaml(), envelope.spec(),
                false, Instant.EPOCH, null);
        return snapshotIntegrity.snapshot(definition, envelope.kind());
    }

    /**
     * 테스트 Target Profile safe snapshot을 만든다.
     *
     * @return 검증된 Profile snapshot
     */
    private io.outcomeprobe.profile.TargetProfileSafeSnapshot profileSnapshot() {
        ArtifactEnvelope envelope = artifactParser.parse(PROFILE_YAML);
        io.outcomeprobe.profile.TargetProfile profile = new io.outcomeprobe.profile.TargetProfile(
                envelope.metadata().name(), envelope.spec().path("baseUri").asText(),
                List.of("/**"), List.of("GET", "POST"), List.of("localhost"), List.of(8091),
                Map.of(), Map.of(), Map.of(), List.of(), List.of());
        io.outcomeprobe.profile.VersionedTargetProfile versioned =
                new io.outcomeprobe.profile.VersionedTargetProfile(
                        envelope.apiVersion(), envelope.metadata().version(),
                        envelope.contentHash().hexValue(), envelope.rawYaml(), envelope.spec(), profile);
        return snapshotIntegrity.snapshot(versioned);
    }
}
