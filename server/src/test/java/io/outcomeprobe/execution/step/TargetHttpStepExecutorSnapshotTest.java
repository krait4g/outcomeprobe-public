package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.execution.TargetHttpPort;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.probe.AssertionEvaluator;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetHeaderResolver;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** target.http가 현재 Profile 저장소가 아닌 Run snapshot 목적지를 쓰는지 검증한다. */
class TargetHttpStepExecutorSnapshotTest {

    /** 새 Profile 객체가 생겨도 실행기는 최초 snapshot base URI를 사용해야 한다. */
    @Test
    void usesProfileCapturedByRun() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TargetProfile captured = profile("http://old-target:8091",
                Map.of("X-Tenant", "default-tenant"),
                Map.of("Authorization", "target-auth"));
        TargetProfile ignoredNewVersion = profile("http://new-target:9091");
        assertThat(ignoredNewVersion.baseUri()).isNotEqualTo(captured.baseUri());
        TargetHttpPort port = mock(TargetHttpPort.class);
        when(port.call(any(), eq("POST"), eq("/v1/test"), anyMap(), eq(null)))
                .thenReturn(new TargetHttpPort.TargetHttpResponse(
                        200, "{}", mapper.createObjectNode(), Instant.EPOCH, null));
        ClockProvider clock = mock(ClockProvider.class);
        when(clock.now()).thenReturn(Instant.EPOCH);
        TargetHttpStepExecutor executor = new TargetHttpStepExecutor(port,
                new TargetHeaderResolver(secretRef -> java.util.Optional.of("Bearer resolved-value")),
                mock(EvidenceRecorder.class), new AssertionEvaluator(), clock, mapper);

        StepExecutionResult result = executor.execute(context(mapper, captured));

        assertThat(result).isInstanceOf(StepExecutionResult.Completed.class);
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(port).call(eq(captured), eq("POST"), eq("/v1/test"), headers.capture(), eq(null));
        assertThat(headers.getValue()).containsEntry("X-Tenant", "default-tenant")
                .containsEntry("Authorization", "Bearer resolved-value");
    }

    /** Profile secret이 없으면 Errored를 반환하고 Target HTTP 포트를 호출하지 않아야 한다. */
    @Test
    void missingSecretNeverCallsTargetPort() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TargetProfile profile = profile("http://old-target:8091", Map.of(),
                Map.of("Authorization", "missing-auth"));
        TargetHttpPort port = mock(TargetHttpPort.class);
        TargetHttpStepExecutor executor = new TargetHttpStepExecutor(port,
                new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                mock(EvidenceRecorder.class), new AssertionEvaluator(), mock(ClockProvider.class), mapper);

        StepExecutionResult result = executor.execute(context(mapper, profile));

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode()).isEqualTo(ProblemCode.TARGET_SECRET_UNRESOLVED));
        verify(port, never()).call(any(), anyString(), anyString(), anyMap(), any());
    }

    /** body 크기 초과는 deadline 재시도 없이 정확한 영구 오류로 끝나야 한다. */
    @Test
    void responseTooLargeStopsImmediately() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TargetHttpPort port = mock(TargetHttpPort.class);
        when(port.call(any(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(new TargetHttpPort.TargetHttpResponse(
                        200, null, null, Instant.EPOCH, "TARGET_RESPONSE_TOO_LARGE"));

        StepExecutionResult result = executor(port, mapper).execute(
                context(mapper, profile("http://old-target:8091")));

        assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                error -> assertThat(error.problemCode())
                        .isEqualTo(ProblemCode.TARGET_RESPONSE_TOO_LARGE));
    }

    /** 일반 연결 실패만 deadline 전 Waiting으로 재시도해야 한다. */
    @Test
    void connectionFailureRetriesBeforeDeadline() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TargetHttpPort port = mock(TargetHttpPort.class);
        when(port.call(any(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(new TargetHttpPort.TargetHttpResponse(
                        0, null, null, Instant.EPOCH, "TARGET_CONNECTION_FAILED"));

        StepExecutionResult result = executor(port, mapper).execute(
                context(mapper, profile("http://old-target:8091")));

        assertThat(result).isInstanceOf(StepExecutionResult.Waiting.class);
    }

    /** interrupt와 TLS·redirect는 연결 재시도로 오인하지 않아야 한다. */
    @Test
    void interruptedTlsAndRedirectFailuresArePermanent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Map.Entry<String, ProblemCode> failure : Map.of(
                "INTERRUPTED", ProblemCode.TARGET_CONNECTION_FAILED,
                "TARGET_TLS_INVALID", ProblemCode.TARGET_TLS_INVALID,
                "TARGET_REDIRECT_REJECTED", ProblemCode.TARGET_RESPONSE_UNEXPECTED).entrySet()) {
            TargetHttpPort port = mock(TargetHttpPort.class);
            when(port.call(any(), anyString(), anyString(), anyMap(), any()))
                    .thenReturn(new TargetHttpPort.TargetHttpResponse(
                            0, null, null, Instant.EPOCH, failure.getKey()));

            StepExecutionResult result = executor(port, mapper).execute(
                    context(mapper, profile("http://old-target:8091")));

            assertThat(result).isInstanceOfSatisfying(StepExecutionResult.Errored.class,
                    error -> assertThat(error.problemCode()).isEqualTo(failure.getValue()));
        }
    }

    /** query를 실제 Target에는 보내되 Timeline Evidence에는 원문을 남기지 않는다. */
    @Test
    void redactsQueryFromTargetRequestEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String sensitivePath = "/test-support/state?key=track-1&token=secret-marker";
        TargetHttpPort port = mock(TargetHttpPort.class);
        when(port.call(any(), eq("GET"), eq(sensitivePath), anyMap(), eq(null)))
                .thenReturn(new TargetHttpPort.TargetHttpResponse(
                        200, "{}", mapper.createObjectNode(), Instant.EPOCH, null));
        EvidenceRecorder recorder = mock(EvidenceRecorder.class);
        ClockProvider clock = mock(ClockProvider.class);
        when(clock.now()).thenReturn(Instant.EPOCH);
        TargetHttpStepExecutor executor = new TargetHttpStepExecutor(port,
                new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                recorder, new AssertionEvaluator(), clock, mapper);

        StepExecutionResult result = executor.execute(
                context(mapper, profile("http://old-target:8091"), sensitivePath, "GET"));

        assertThat(result).isInstanceOf(StepExecutionResult.Completed.class);
        ArgumentCaptor<AppendEvidenceCommand> evidence =
                ArgumentCaptor.forClass(AppendEvidenceCommand.class);
        verify(recorder, org.mockito.Mockito.atLeastOnce()).append(evidence.capture());
        AppendEvidenceCommand request = evidence.getAllValues().stream()
                .filter(command -> command.type() == EvidenceType.TARGET_REQUEST_SENT)
                .findFirst()
                .orElseThrow();
        assertThat(request.attributes())
                .containsEntry("path", "/test-support/state")
                .containsEntry("pathQueryRedacted", "true");
        assertThat(request.toString()).doesNotContain("secret-marker");
    }

    /** 고정 clock과 비밀 없는 resolver로 Target HTTP 실행기를 만든다. */
    private TargetHttpStepExecutor executor(TargetHttpPort port, ObjectMapper mapper) {
        ClockProvider clock = mock(ClockProvider.class);
        when(clock.now()).thenReturn(Instant.EPOCH);
        return new TargetHttpStepExecutor(port,
                new TargetHeaderResolver(secretRef -> java.util.Optional.empty()),
                mock(EvidenceRecorder.class), new AssertionEvaluator(), clock, mapper);
    }

    /** 테스트용 Profile을 만든다. */
    private TargetProfile profile(String baseUri) {
        return profile(baseUri, Map.of(), Map.of());
    }

    /** header 구성을 지정해 테스트용 Profile을 만든다. */
    private TargetProfile profile(String baseUri, Map<String, String> defaultHeaders,
                                  Map<String, String> secretHeaders) {
        return new TargetProfile("target", baseUri, List.of("/**"), List.of("POST"),
                List.of(), List.of(), defaultHeaders, secretHeaders,
                Map.of(), List.of(), List.of());
    }

    /** 지정 Profile snapshot을 가진 target.http 실행 문맥을 만든다. */
    private StepExecutionContext context(ObjectMapper mapper, TargetProfile profile) throws Exception {
        return context(mapper, profile, "/v1/test", "POST");
    }

    /** 지정한 요청 경로와 method를 가진 target.http 실행 문맥을 만든다. */
    private StepExecutionContext context(ObjectMapper mapper, TargetProfile profile,
                                         String path, String method) throws Exception {
        String sourceHash = HashValue.sha256("old-profile").hexValue();
        String canonicalHash = TargetProfileSafeSnapshot.calculateCanonicalHash(mapper,
                "outcomeprobe.io/v1alpha1", "target", "1.0.0", sourceHash, profile);
        TargetProfileSafeSnapshot snapshot = new TargetProfileSafeSnapshot(
                "outcomeprobe.io/v1alpha1", "target", "1.0.0", sourceHash,
                canonicalHash, profile);
        CompiledRunPlan plan = new CompiledRunPlan("run-1", "s", "1.0.0", "h",
                "c", "1.0.0", "alias", "target", 1L, 30_000L, Instant.EPOCH,
                Map.of(), List.of(), 2, null, null, snapshot, "set");
        ScenarioRun run = new ScenarioRun("run-1", "s", "1.0.0", "h", "target", 1L,
                io.outcomeprobe.execution.RunStatus.RUNNING, 0, mapper.valueToTree(plan),
                Instant.EPOCH, Instant.EPOCH.plusSeconds(30), null, null, 0, 0,
                false, null, null, Instant.EPOCH, null, Instant.EPOCH);
        var input = mapper.createObjectNode();
        input.putObject("request").put("method", method).put("path", path);
        input.putObject("expect").put("status", 200);
        RunStepExecution step = new RunStepExecution("row", "run-1", "call", 0,
                StepType.TARGET_HTTP, StepStatus.PENDING, input, null, null, null,
                Instant.EPOCH.plusSeconds(30), 0,
                null, null, 0, null, null);
        return new StepExecutionContext(run, plan, step);
    }
}
