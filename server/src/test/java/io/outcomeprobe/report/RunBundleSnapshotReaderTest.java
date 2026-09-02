package io.outcomeprobe.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.RunStepPort;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.ScenarioRunPort;
import io.outcomeprobe.execution.StepStatus;
import io.outcomeprobe.execution.StepType;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bundle snapshot 조회의 단일 조회·트랜잭션 격리·fail-closed 계약을 검증한다.
 */
class RunBundleSnapshotReaderTest {

    private final ScenarioRunPort scenarioRunPort = mock(ScenarioRunPort.class);
    private final RunStepPort runStepPort = mock(RunStepPort.class);
    private final EvidenceQueryPort evidenceQueryPort = mock(EvidenceQueryPort.class);
    private final RunBundleSnapshotReader reader = new RunBundleSnapshotReader(
            scenarioRunPort, runStepPort, evidenceQueryPort);

    /**
     * 세 저장소를 각각 한 번만 조회하고 반환 목록을 방어적으로 고정한다.
     */
    @Test
    @DisplayName("Run·Step·Evidence를 각각 한 번 조회해 불변 snapshot으로 고정한다")
    void readsEachPortOnceAndCopiesCollections() {
        ScenarioRun run = run("run-1");
        RunStepExecution step = step("run-1", 0);
        EvidenceEvent evidence = evidence("run-1", 1L);
        List<RunStepExecution> mutableSteps = new ArrayList<>(List.of(step));
        List<EvidenceEvent> mutableTimeline = new ArrayList<>(List.of(evidence));
        when(scenarioRunPort.findById("run-1")).thenReturn(Optional.of(run));
        when(runStepPort.findByRunId("run-1")).thenReturn(mutableSteps);
        when(evidenceQueryPort.timeline("run-1")).thenReturn(mutableTimeline);

        RunBundleSnapshot snapshot = reader.read("run-1").orElseThrow();
        mutableSteps.clear();
        mutableTimeline.clear();

        assertThat(snapshot.run()).isSameAs(run);
        assertThat(snapshot.steps()).containsExactly(step);
        assertThat(snapshot.timeline()).containsExactly(evidence);
        assertThatThrownBy(() -> snapshot.steps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.timeline().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        verify(scenarioRunPort, times(1)).findById("run-1");
        verify(runStepPort, times(1)).findByRunId("run-1");
        verify(evidenceQueryPort, times(1)).timeline("run-1");
        verifyNoMoreInteractions(scenarioRunPort, runStepPort, evidenceQueryPort);
    }

    /**
     * Run이 없으면 다른 두 저장소를 조회하지 않는다.
     */
    @Test
    @DisplayName("Run이 없으면 Step과 Evidence 조회 없이 empty를 반환한다")
    void missingRunSkipsDependentQueries() {
        when(scenarioRunPort.findById("missing")).thenReturn(Optional.empty());

        assertThat(reader.read("missing")).isEmpty();

        verify(scenarioRunPort, times(1)).findById("missing");
        verify(runStepPort, never()).findByRunId("missing");
        verify(evidenceQueryPort, never()).timeline("missing");
    }

    /**
     * 다른 Run의 행이 섞인 저장소 결과를 부분 snapshot으로 허용하지 않는다.
     */
    @Test
    @DisplayName("다른 Run의 Step 또는 Evidence가 섞이면 fail-closed 처리한다")
    void rejectsRowsBoundToAnotherRun() {
        when(scenarioRunPort.findById("run-1")).thenReturn(Optional.of(run("run-1")));
        when(runStepPort.findByRunId("run-1")).thenReturn(List.of(step("run-2", 0)));
        when(evidenceQueryPort.timeline("run-1")).thenReturn(List.of(evidence("run-1", 1L)));

        assertThatThrownBy(() -> reader.read("run-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다른 Run의 Step");
    }

    /**
     * 읽기 메서드가 새 REPEATABLE_READ 읽기 전용 트랜잭션을 선언하는지 검증한다.
     *
     * @throws NoSuchMethodException 조회 API가 사라지면 테스트를 실패시킨다
     */
    @Test
    @DisplayName("Bundle 조회는 REQUIRES_NEW REPEATABLE_READ 읽기 전용 트랜잭션이다")
    void declaresRequiredTransactionBoundary() throws NoSuchMethodException {
        Method method = RunBundleSnapshotReader.class.getMethod("read", String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    /**
     * 테스트용 Run snapshot을 만든다.
     *
     * @param runId Run 식별자
     * @return terminal Run fixture
     */
    private ScenarioRun run(String runId) {
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        return new ScenarioRun(runId, "scenario", "1.0.0", "hash", "profile", 7L,
                RunStatus.PASSED, 0, null, null, now.plusSeconds(10), null, null,
                1L, 1L, false, null, null, now, now.plusSeconds(1), now);
    }

    /**
     * 테스트용 Step snapshot을 만든다.
     *
     * @param runId 소속 Run 식별자
     * @param ordinal Step 순서
     * @return 통과 Step fixture
     */
    private RunStepExecution step(String runId, int ordinal) {
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        return new RunStepExecution("step-row-" + ordinal, runId, "step-" + ordinal,
                ordinal, StepType.PROVIDER_EXPECT, StepStatus.PASSED, null, null,
                null, null, now.plusSeconds(10), 1, null, null, 1L, now, now.plusMillis(10));
    }

    /**
     * 테스트용 Evidence snapshot을 만든다.
     *
     * @param runId 소속 Run 식별자
     * @param sequence Run 내부 순서
     * @return Evidence fixture
     */
    private EvidenceEvent evidence(String runId, long sequence) {
        Instant now = Instant.parse("2026-08-06T00:00:01Z");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("status", "PASSED");
        return new EvidenceEvent("event-" + sequence, runId, sequence, 10L, now,
                EvidenceType.RUN_PASSED, EvidenceSource.ENGINE, null, null, null, null,
                attributes, null, null, false, now);
    }
}
