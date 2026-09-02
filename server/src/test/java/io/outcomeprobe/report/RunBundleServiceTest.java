package io.outcomeprobe.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.ArtifactSnapshot;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunSnapshotIntegrity;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.profile.TargetProfileSpecMapper;
import io.outcomeprobe.profile.VersionedTargetProfile;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * strict Run Bundle의 8-entry 구조·JSON·safe snapshot·fail-closed 정책을 검증한다.
 */
class RunBundleServiceTest {

    private static final String API_VERSION = "outcomeprobe.io/v1alpha1";
    private static final List<String> EXPECTED_ENTRIES = List.of(
            "scenario.yaml",
            "provider-contract.yaml",
            "compiled-run-plan.json",
            "report.json",
            "report.html",
            "junit.xml",
            "timeline.json",
            "README.txt");

    private final RunBundleSnapshotReader snapshotReader = mock(RunBundleSnapshotReader.class);
    private final JsonReportService jsonReportService = mock(JsonReportService.class);
    private final HtmlReportService htmlReportService = mock(HtmlReportService.class);
    private final JUnitXmlReportService junitXmlReportService = mock(JUnitXmlReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RunSnapshotIntegrity snapshotIntegrity = new RunSnapshotIntegrity(objectMapper);
    private Map<String, Object> reportFixture;
    private RunBundleService service;

    /** 테스트 대상과 공통 report fixture를 준비한다. */
    @BeforeEach
    void setUp() {
        service = new RunBundleService(snapshotReader, jsonReportService, htmlReportService,
                junitXmlReportService, objectMapper, snapshotIntegrity);
        reportFixture = new LinkedHashMap<>();
        reportFixture.put("runId", "run-1");
        reportFixture.put("status", "PASSED");
        reportFixture.put("summary", Map.of("stepsPassed", 2, "stepsFailed", 0));
        when(jsonReportService.buildReport(any(RunBundleSnapshot.class)))
                .thenReturn(reportFixture);
        when(htmlReportService.render(same(reportFixture)))
                .thenReturn("<!DOCTYPE html><html lang=\"ko\"><body>PASSED</body></html>");
        when(junitXmlReportService.buildReport(any(RunBundleSnapshot.class)))
                .thenReturn("<testsuites tests=\"1\" failures=\"0\"/>");
    }

    /** 여덟 파일이 정확한 이름과 실제 직렬화 내용으로 생성돼야 한다. */
    @Test
    @DisplayName("v2 Run Bundle은 정확한 8개 파일과 parse 가능한 JSON을 포함한다")
    void createsExactEightEntryBundleWithParseableJson() throws Exception {
        CompiledRunPlan plan = validPlan();
        RunBundleSnapshot snapshot = snapshot(run(plan));
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot));

        ZipContents zip = unzip(service.buildBundle("run-1").orElseThrow());

        assertThat(zip.names()).containsExactlyElementsOf(EXPECTED_ENTRIES);
        assertThat(zip.entries()).hasSize(8).doesNotContainKey("compiled-plan.json");
        assertThat(objectMapper.readTree(zip.entries().get("report.json")).path("status").asText())
                .isEqualTo("PASSED");
        assertThat(objectMapper.readTree(zip.entries().get("compiled-run-plan.json"))
                .path("planSchemaVersion").asInt()).isEqualTo(2);
        JsonNode timeline = objectMapper.readTree(zip.entries().get("timeline.json"));
        assertThat(timeline.isArray()).isTrue();
        assertThat(timeline.get(0).path("logicalTimeMs").asLong()).isEqualTo(125L);
        assertThat(timeline.get(0).path("observedAt").asText())
                .isEqualTo("2026-08-06T00:00:01Z");
        assertThat(zip.entries().get("report.html"))
                .startsWith("<!DOCTYPE html>")
                .contains("PASSED");
        assertThat(zip.entries().get("provider-contract.yaml"))
                .isEqualTo(plan.contractSnapshot().safeRawYaml());
        verify(snapshotReader, times(1)).read("run-1");
        verify(jsonReportService, times(1)).buildReport(same(snapshot));
        verify(junitXmlReportService, times(1)).buildReport(same(snapshot));
        verify(htmlReportService, times(1)).render(same(reportFixture));
        verify(jsonReportService, never()).buildReport("run-1");
        verify(junitXmlReportService, never()).buildReport("run-1");
        verify(htmlReportService, never()).buildReport("run-1");
    }

    /** Bundle YAML은 검증된 snapshot 원문과 safe content hash에 정확히 결속돼야 한다. */
    @Test
    @DisplayName("Scenario와 Contract YAML은 v2 safe snapshot 및 hash와 일치한다")
    void exportsSnapshotYamlWithMatchingSafeHashes() throws Exception {
        CompiledRunPlan plan = validPlan();
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot(run(plan))));

        ZipContents zip = unzip(service.buildBundle("run-1").orElseThrow());
        String scenarioYaml = zip.entries().get("scenario.yaml");
        String contractYaml = zip.entries().get("provider-contract.yaml");

        assertThat(scenarioYaml).isEqualTo(plan.scenarioSnapshot().safeRawYaml());
        assertThat(contractYaml).isEqualTo(plan.contractSnapshot().safeRawYaml());
        assertThat(HashValue.sha256(scenarioYaml).hexValue())
                .isEqualTo(plan.scenarioSnapshot().safeContentHash());
        assertThat(HashValue.sha256(contractYaml).hexValue())
                .isEqualTo(plan.contractSnapshot().safeContentHash());
        assertThat(scenarioYaml).doesNotContain("scenario-import-comment");
        assertThat(contractYaml).doesNotContain("contract-import-comment");
    }

    /** snapshot이 없는 v1 plan을 현재 Artifact로 보정하지 않고 즉시 거부해야 한다. */
    @Test
    @DisplayName("legacy v1 plan의 strict Bundle 생성을 fail-closed로 거부한다")
    void rejectsLegacyPlanBeforeBuildingReports() {
        CompiledRunPlan legacy = new CompiledRunPlan(
                "run-1", "test-scenario", "1.0.0", HashValue.sha256("scenario").hexValue(),
                "test-contract", "1.0.0", "test-alias", "test-target", 7L, 30_000L,
                Instant.EPOCH, Map.of(), List.of());
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot(run(legacy))));

        assertThatThrownBy(() -> service.buildBundle("run-1"))
                .isInstanceOfSatisfying(BundleExportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED));
        verifyNoInteractions(jsonReportService, htmlReportService, junitXmlReportService);
    }

    /** safe YAML이 변조된 v2 snapshot도 hash 재계산 없이 거부돼야 한다. */
    @Test
    @DisplayName("손상된 v2 snapshot의 strict Bundle 생성을 fail-closed로 거부한다")
    void rejectsCorruptedSnapshotBeforeBuildingReports() {
        CompiledRunPlan valid = validPlan();
        ArtifactSnapshot scenario = valid.scenarioSnapshot();
        ArtifactSnapshot corrupted = new ArtifactSnapshot(
                scenario.kind(), scenario.apiVersion(), scenario.name(), scenario.version(),
                scenario.sourceContentHash(), scenario.safeContentHash(),
                scenario.canonicalSnapshotHash(), scenario.safeRawYaml() + "tampered: true\n",
                scenario.safeSpec());
        CompiledRunPlan plan = new CompiledRunPlan(
                valid.runId(), valid.scenarioName(), valid.scenarioVersion(), valid.scenarioHash(),
                valid.contractName(), valid.contractVersion(), valid.emulatorAlias(),
                valid.targetProfileName(), valid.seed(), valid.timeoutMs(), valid.createdAt(),
                valid.variables(), valid.steps(), valid.planSchemaVersion(), corrupted,
                valid.contractSnapshot(), valid.targetProfileSnapshot(), valid.snapshotSetHash());
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot(run(plan))));

        assertThatThrownBy(() -> service.buildBundle("run-1"))
                .isInstanceOfSatisfying(BundleExportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED));
        verifyNoInteractions(jsonReportService, htmlReportService, junitXmlReportService);
    }

    /** compiled plan 자체가 없으면 null을 역직렬화하거나 live Artifact를 찾지 않아야 한다. */
    @Test
    @DisplayName("compiled plan이 null이면 snapshot 손상 오류로 Bundle을 거부한다")
    void rejectsNullCompiledPlan() {
        ScenarioRun run = new ScenarioRun(
                "run-1", "test-scenario", "1.0.0", HashValue.sha256("source").hexValue(),
                "test-target", 7L, RunStatus.PASSED, 0, null,
                null, null, null, null, 1L, 1L, false,
                null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), Instant.EPOCH);
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot(run)));

        assertThatThrownBy(() -> service.buildBundle("run-1"))
                .isInstanceOfSatisfying(BundleExportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED));
        verifyNoInteractions(jsonReportService, htmlReportService, junitXmlReportService);
    }

    /** 검증기 내부 RuntimeException도 raw 원인을 노출하지 않는 snapshot 오류로 정규화한다. */
    @Test
    @DisplayName("snapshot 검증 RuntimeException도 안전한 Bundle 오류로 정규화한다")
    void normalizesUnexpectedSnapshotVerificationFailure() {
        CompiledRunPlan plan = validPlan();
        RunSnapshotIntegrity failingIntegrity = mock(RunSnapshotIntegrity.class);
        doThrow(new NullPointerException("raw-secret-from-corrupt-plan"))
                .when(failingIntegrity).verify(any(CompiledRunPlan.class));
        RunBundleService failingService = new RunBundleService(
                snapshotReader, jsonReportService, htmlReportService, junitXmlReportService,
                objectMapper, failingIntegrity);
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot(run(plan))));

        assertThatThrownBy(() -> failingService.buildBundle("run-1"))
                .isInstanceOfSatisfying(BundleExportException.class, exception -> {
                    assertThat(exception.problemCode())
                            .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED);
                    assertThat(exception.getMessage())
                            .doesNotContain("raw-secret-from-corrupt-plan");
                });
    }

    /** Run의 flat metadata와 plan이 다르면 다른 실행 조건의 snapshot을 내보내지 않아야 한다. */
    @Test
    @DisplayName("Run과 compiled plan metadata가 다르면 Bundle을 거부한다")
    void rejectsPlanBoundToAnotherRun() {
        CompiledRunPlan plan = validPlan();
        ScenarioRun mismatched = new ScenarioRun(
                "run-1", plan.scenarioName(), plan.scenarioVersion(), plan.scenarioHash(),
                plan.targetProfileName(), plan.seed() + 1, RunStatus.PASSED, 0,
                objectMapper.valueToTree(plan), null, null, null, null, 1L, 1L, false,
                null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), Instant.EPOCH);
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot(mismatched)));

        assertThatThrownBy(() -> service.buildBundle("run-1"))
                .isInstanceOfSatisfying(BundleExportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED));
    }

    /** 필수 report 하나라도 없으면 빈 대체 파일이 든 Bundle을 만들지 않아야 한다. */
    @Test
    @DisplayName("필수 report가 없으면 부분 Bundle 대신 내부 오류를 반환한다")
    void rejectsMissingRequiredReport() {
        CompiledRunPlan plan = validPlan();
        when(snapshotReader.read("run-1")).thenReturn(Optional.of(snapshot(run(plan))));
        when(junitXmlReportService.buildReport(any(RunBundleSnapshot.class))).thenReturn("");

        assertThatThrownBy(() -> service.buildBundle("run-1"))
                .isInstanceOfSatisfying(BundleExportException.class, exception ->
                        assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.INTERNAL_EXECUTION_ERROR));
    }

    /** 같은 시점의 Run·Evidence를 담은 Bundle snapshot fixture를 만든다. */
    private RunBundleSnapshot snapshot(ScenarioRun run) {
        Instant observedAt = Instant.parse("2026-08-06T00:00:01Z");
        EvidenceEvent event = new EvidenceEvent("e-1", run.id(), 1L, 125L,
                observedAt, EvidenceType.RUN_PASSED, EvidenceSource.ENGINE,
                null, null, null, null, Map.of("status", "PASSED"),
                null, null, false, observedAt);
        return new RunBundleSnapshot(run, List.of(), List.of(event));
    }

    /** 검증 가능한 Scenario·Contract·Profile snapshot을 가진 plan을 만든다. */
    private CompiledRunPlan validPlan() {
        ObjectNode scenarioSpec = objectMapper.createObjectNode();
        scenarioSpec.putObject("contractRef")
                .put("name", "test-contract")
                .put("version", "1.0.0");
        scenarioSpec.putArray("steps");
        ArtifactSnapshot scenario = artifactSnapshot(ArtifactKind.SCENARIO,
                "test-scenario", "scenario-import-comment", scenarioSpec);
        ArtifactSnapshot contract = artifactSnapshot(ArtifactKind.PROVIDER_CONTRACT,
                "test-contract", "contract-import-comment",
                objectMapper.createObjectNode().put("emulatorAlias", "test-alias"));
        TargetProfileSafeSnapshot profile = profileSnapshot();
        String setHash = RunSnapshotIntegrity.snapshotSetHash(
                CompiledRunPlan.CURRENT_SCHEMA_VERSION, scenario, contract, profile);
        return new CompiledRunPlan(
                "run-1", scenario.name(), scenario.version(), scenario.sourceContentHash(),
                contract.name(), contract.version(), "test-alias", profile.name(), 7L, 30_000L,
                Instant.EPOCH, Map.of("paymentId", "p-1"), List.of(),
                CompiledRunPlan.CURRENT_SCHEMA_VERSION, scenario, contract, profile, setHash);
    }

    /** comment가 포함된 import 원본에서 comment-free safe Artifact snapshot을 만든다. */
    private ArtifactSnapshot artifactSnapshot(ArtifactKind kind, String name,
                                              String commentMarker, JsonNode spec) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("apiVersion", API_VERSION);
        root.put("kind", kind == ArtifactKind.SCENARIO ? "Scenario" : "ProviderContract");
        root.putObject("metadata")
                .put("name", name)
                .put("displayName", name)
                .put("version", "1.0.0");
        root.set("spec", spec);
        String rawYaml;
        try {
            rawYaml = new ObjectMapper(new YAMLFactory()).writeValueAsString(root)
                    + "# " + commentMarker + "\n";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        ArtifactDefinition definition = new ArtifactDefinition(
                name + "-id", kind, new ArtifactMetadata(name, name, "1.0.0", Map.of()),
                API_VERSION, HashValue.sha256(rawYaml), rawYaml, spec, false,
                Instant.EPOCH, null);
        return snapshotIntegrity.snapshot(definition, kind);
    }

    /** secret 원문이 없는 Target Profile safe snapshot을 만든다. */
    private TargetProfileSafeSnapshot profileSnapshot() {
        ObjectNode spec = objectMapper.createObjectNode();
        spec.put("baseUri", "http://localhost:8091");
        spec.putArray("allowedPaths").add("/**");
        spec.putArray("allowedMethods").add("GET");
        spec.putArray("allowedHosts").add("localhost");
        spec.putArray("allowedPorts").add(8091);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("apiVersion", API_VERSION);
        root.put("kind", "TargetProfile");
        root.putObject("metadata")
                .put("name", "test-target")
                .put("version", "1.0.0");
        root.set("spec", spec);
        String rawYaml;
        try {
            rawYaml = new ObjectMapper(new YAMLFactory()).writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        TargetProfile profile = new TargetProfileSpecMapper().toProfile("test-target", spec);
        VersionedTargetProfile versioned = new VersionedTargetProfile(
                API_VERSION, "1.0.0", HashValue.sha256(rawYaml).hexValue(), rawYaml,
                spec, profile);
        return snapshotIntegrity.snapshot(versioned);
    }

    /** compiled plan을 JSONB와 같은 JsonNode에 넣은 Run fixture를 만든다. */
    private ScenarioRun run(CompiledRunPlan plan) {
        return new ScenarioRun(
                "run-1", plan.scenarioName(), plan.scenarioVersion(), plan.scenarioHash(),
                plan.targetProfileName(), plan.seed(), RunStatus.PASSED, 0,
                objectMapper.valueToTree(plan), null, null, null, null, 1L, 1L, false,
                null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), Instant.EPOCH);
    }

    /** ZIP bytes를 entry 이름 순서와 UTF-8 내용으로 읽는다. */
    private ZipContents unzip(byte[] bytes) throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new ZipContents(List.copyOf(names), Map.copyOf(entries));
    }

    /**
     * 테스트에서 해석한 ZIP 이름 순서와 entry 내용을 보관한다.
     *
     * @param names ZIP entry 이름 순서
     * @param entries entry 이름별 UTF-8 내용
     */
    private record ZipContents(List<String> names, Map<String, String> entries) {
    }
}
