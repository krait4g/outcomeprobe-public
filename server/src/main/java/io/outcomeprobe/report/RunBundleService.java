package io.outcomeprobe.report;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunSnapshotIntegrity;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.foundation.ProblemCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Run 전체 증거를 재현 가능한 strict ZIP Bundle로 묶는다.
 *
 * <p>책임: Run 시작 시 고정한 v2 Scenario·ProviderContract snapshot, compiled plan,
 * JSON·HTML·JUnit report와 Timeline을 정확히 여덟 개의 파일로 내보낸다.</p>
 * <p>핵심 불변식: 현재 Artifact 저장소를 다시 조회하지 않는다. legacy plan이거나
 * snapshot 구조·hash·Run 결속이 손상된 경우 현재 Artifact로 보정하지 않고 Bundle 생성을
 * 거부한다. 일부 파일만 있거나 JSON 직렬화가 실패한 Bundle도 반환하지 않는다.</p>
 */
@Service
public class RunBundleService {

    private static final List<String> ENTRY_NAMES = List.of(
            "scenario.yaml",
            "provider-contract.yaml",
            "compiled-run-plan.json",
            "report.json",
            "report.html",
            "junit.xml",
            "timeline.json",
            "README.txt");

    private final RunBundleSnapshotReader snapshotReader;
    private final JsonReportService jsonReportService;
    private final HtmlReportService htmlReportService;
    private final JUnitXmlReportService junitXmlReportService;
    private final ObjectMapper objectMapper;
    private final RunSnapshotIntegrity snapshotIntegrity;

    /**
     * Bundle 생성에 필요한 조회·report·snapshot 검증 협업 객체를 주입한다.
     *
     * @param snapshotReader Run·Step·Evidence 단일 읽기 snapshot 조회기
     * @param jsonReportService JSON report 서비스
     * @param htmlReportService standalone HTML report 서비스
     * @param junitXmlReportService JUnit report 서비스
     * @param objectMapper JSON 변환 mapper
     * @param snapshotIntegrity v2 snapshot 무결성 검증기
     */
    public RunBundleService(RunBundleSnapshotReader snapshotReader,
                            JsonReportService jsonReportService,
                            HtmlReportService htmlReportService,
                            JUnitXmlReportService junitXmlReportService,
                            ObjectMapper objectMapper,
                            RunSnapshotIntegrity snapshotIntegrity) {
        this.snapshotReader = snapshotReader;
        this.jsonReportService = jsonReportService;
        this.htmlReportService = htmlReportService;
        this.junitXmlReportService = junitXmlReportService;
        this.objectMapper = objectMapper;
        this.snapshotIntegrity = snapshotIntegrity;
    }

    /**
     * Run Bundle ZIP을 생성한다.
     *
     * <p>ZIP을 쓰기 전에 여덟 파일의 내용을 모두 생성한다. 따라서 report 생성 또는
     * snapshot 검증 하나라도 실패하면 부분 Bundle이 성공 응답으로 노출되지 않는다.</p>
     *
     * @param runId Run 식별자
     * @return ZIP 바이트, Run이 없으면 empty
     * @throws BundleExportException snapshot 또는 직렬화가 안전하게 완료되지 않으면 발생한다
     */
    public Optional<byte[]> buildBundle(String runId) {
        RunBundleSnapshot snapshot;
        try {
            Optional<RunBundleSnapshot> snapshotOpt = snapshotReader.read(runId);
            if (snapshotOpt.isEmpty()) {
                return Optional.empty();
            }
            snapshot = snapshotOpt.get();
        } catch (RuntimeException exception) {
            throw generationFailed(exception);
        }
        CompiledRunPlan plan = verifiedPlan(snapshot.run());
        Map<String, String> entries = assembleEntries(snapshot, plan);
        return Optional.of(writeZip(entries));
    }

    /** 저장된 compiled plan을 복원하고 v2 snapshot 및 Run 결속을 검증한다. */
    private CompiledRunPlan verifiedPlan(ScenarioRun run) {
        if (run.compiledPlan() == null) {
            throw snapshotUnavailable(null);
        }
        CompiledRunPlan plan;
        try {
            plan = objectMapper.treeToValue(run.compiledPlan(), CompiledRunPlan.class);
        } catch (JacksonException exception) {
            throw snapshotUnavailable(exception);
        } catch (RuntimeException exception) {
            throw snapshotUnavailable(exception);
        }
        try {
            snapshotIntegrity.verify(plan);
        } catch (RuntimeException exception) {
            throw snapshotUnavailable(exception);
        }
        if (!Objects.equals(run.id(), plan.runId())
                || !Objects.equals(run.scenarioName(), plan.scenarioName())
                || !Objects.equals(run.scenarioVersion(), plan.scenarioVersion())
                || !Objects.equals(run.scenarioHash(), plan.scenarioHash())
                || !Objects.equals(run.targetProfileName(), plan.targetProfileName())
                || run.seed() != plan.seed()) {
            throw snapshotUnavailable(null);
        }
        return plan;
    }

    /** 명세 순서대로 정확히 여덟 파일의 내용을 완성한다. */
    private Map<String, String> assembleEntries(RunBundleSnapshot snapshot,
                                                CompiledRunPlan plan) {
        ScenarioRun run = snapshot.run();
        Map<String, Object> report = jsonReportService.buildReport(snapshot);
        if (report.isEmpty()) {
            throw reportUnavailable();
        }
        String html;
        try {
            html = htmlReportService.render(report);
        } catch (ReportSerializationException exception) {
            throw generationFailed(exception);
        }
        String junit = requiredDocument(
                junitXmlReportService.buildReport(snapshot), "junit.xml");
        if (html.isBlank()) {
            throw generationFailed(null);
        }

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("scenario.yaml", plan.scenarioSnapshot().safeRawYaml());
        entries.put("provider-contract.yaml", plan.contractSnapshot().safeRawYaml());
        entries.put("compiled-run-plan.json", json(plan));
        entries.put("report.json", json(report));
        entries.put("report.html", html);
        entries.put("junit.xml", junit);
        entries.put("timeline.json", json(timeline(snapshot.timeline())));
        entries.put("README.txt", readme(run, plan));
        if (!List.copyOf(entries.keySet()).equals(ENTRY_NAMES)) {
            throw generationFailed(null);
        }
        return entries;
    }

    /** 필수 문자열 report가 존재하고 비어 있지 않은지 확인한다. */
    private String requiredDocument(String document, String entryName) {
        if (document == null || document.isBlank()) {
            throw new BundleExportException(
                    ProblemCode.INTERNAL_EXECUTION_ERROR,
                    "Run Bundle 필수 문서를 생성할 수 없습니다: " + entryName,
                    null);
        }
        return document;
    }

    /** Evidence Timeline을 JSON 직렬화 가능한 허용 필드 map으로 변환한다. */
    private List<Map<String, Object>> timeline(List<EvidenceEvent> events) {
        return events.stream()
                .map(event -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("sequence", event.sequence());
                    entry.put("logicalTimeMs", event.logicalTimeMs());
                    entry.put("type", event.type().name());
                    entry.put("source", event.source().name());
                    entry.put("stepId", event.stepId());
                    entry.put("attributes", event.attributes());
                    entry.put("payloadSummary", event.payloadSummary());
                    entry.put("bodyHash", event.bodyHash());
                    entry.put("redactionApplied", event.redactionApplied());
                    entry.put("observedAt",
                            event.observedAt() == null ? null : event.observedAt().toString());
                    return entry;
                })
                .toList();
    }

    /** 객체를 실제 JSON 문서로 직렬화하며 실패 시 빈 대체 문서를 만들지 않는다. */
    private String json(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException exception) {
            throw generationFailed(exception);
        }
    }

    /** 모든 내용을 UTF-8 ZIP entry로 기록한다. */
    private byte[] writeZip(Map<String, String> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw generationFailed(exception);
        }
    }

    /** Bundle 구성과 snapshot provenance를 설명하는 안내문을 만든다. */
    private String readme(ScenarioRun run, CompiledRunPlan plan) {
        return """
                OutcomeProbe Run Bundle
                =======================
                Run                  : %s
                Scenario             : %s@%s
                Scenario safe hash   : %s
                Provider contract    : %s@%s
                Contract safe hash   : %s
                Snapshot set hash    : %s
                Status               : %s

                이 Bundle은 Run 시작 시 고정되고 무결성 검증을 통과한 v2 safe snapshot입니다.
                scenario.yaml과 provider-contract.yaml에는 comment와 실제 secret 값이 없습니다.
                logicalTimeMs는 계획상 상대 시간이며 observedAt은 실제 관측 시각입니다.
                """.formatted(
                run.id(),
                plan.scenarioSnapshot().name(), plan.scenarioSnapshot().version(),
                plan.scenarioSnapshot().safeContentHash(),
                plan.contractSnapshot().name(), plan.contractSnapshot().version(),
                plan.contractSnapshot().safeContentHash(),
                plan.snapshotSetHash(), run.status());
    }

    /** legacy 또는 손상 snapshot을 strict Bundle 거부 예외로 변환한다. */
    private BundleExportException snapshotUnavailable(Throwable cause) {
        return new BundleExportException(
                ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED,
                "Run Bundle을 생성할 수 없습니다. 저장된 v2 Artifact snapshot이 없거나 손상되었습니다.",
                cause);
    }

    /** Run은 있으나 필수 report를 만들 수 없는 경우의 일관된 예외를 만든다. */
    private BundleExportException reportUnavailable() {
        return generationFailed(null);
    }

    /** ZIP 또는 JSON 생성 실패를 원문 세부 정보가 없는 내부 오류로 변환한다. */
    private BundleExportException generationFailed(Throwable cause) {
        return new BundleExportException(
                ProblemCode.INTERNAL_EXECUTION_ERROR,
                "Run Bundle 생성 중 내부 오류가 발생했습니다.",
                cause);
    }
}
