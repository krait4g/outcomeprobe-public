package io.outcomeprobe.report;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import java.util.List;
import java.util.Objects;

/**
 * 하나의 Run Bundle을 만드는 데 사용할 Run·Step·Evidence의 일관된 읽기 snapshot이다.
 *
 * <p>책임: 서로 다른 report가 각기 다른 시점의 live 상태를 다시 조회하지 않도록,
 * 한 트랜잭션에서 읽은 세 자료 집합을 불변 목록으로 고정한다.</p>
 * <p>핵심 불변식: 모든 Step과 Evidence는 같은 Run에 속하고 저장소의 정렬 계약을
 * 만족해야 한다. null, 다른 Run의 행, 중복·역순 식별자가 하나라도 있으면 일부 자료를
 * 버리고 진행하지 않고 즉시 거부한다.</p>
 *
 * @param run Bundle 대상 Run
 * @param steps ordinal 오름차순으로 고정한 Step snapshot
 * @param timeline sequence 오름차순으로 고정한 Evidence snapshot
 */
public record RunBundleSnapshot(
        ScenarioRun run,
        List<RunStepExecution> steps,
        List<EvidenceEvent> timeline) {

    /**
     * 입력 자료를 방어적으로 복사하고 Run 결속·정렬 불변식을 검증한다.
     */
    public RunBundleSnapshot {
        Objects.requireNonNull(run, "Bundle Run snapshot은 null일 수 없습니다.");
        Objects.requireNonNull(steps, "Bundle Step snapshot은 null일 수 없습니다.");
        Objects.requireNonNull(timeline, "Bundle Evidence snapshot은 null일 수 없습니다.");
        if (run.id() == null || run.id().isBlank()) {
            throw new IllegalArgumentException("Bundle Run 식별자는 비어 있을 수 없습니다.");
        }
        steps = List.copyOf(steps);
        timeline = List.copyOf(timeline);
        validateSteps(run.id(), steps);
        validateTimeline(run.id(), timeline);
    }

    /**
     * Step이 모두 대상 Run에 속하고 ordinal이 엄격히 증가하는지 확인한다.
     *
     * @param runId 대상 Run 식별자
     * @param steps 검증할 Step 목록
     */
    private static void validateSteps(String runId, List<RunStepExecution> steps) {
        int previousOrdinal = Integer.MIN_VALUE;
        for (RunStepExecution step : steps) {
            if (!Objects.equals(runId, step.runId())) {
                throw new IllegalArgumentException("다른 Run의 Step이 Bundle snapshot에 포함됐습니다.");
            }
            if (step.ordinal() <= previousOrdinal) {
                throw new IllegalArgumentException("Bundle Step ordinal이 중복되거나 역순입니다.");
            }
            previousOrdinal = step.ordinal();
        }
    }

    /**
     * Evidence가 모두 대상 Run에 속하고 sequence가 엄격히 증가하는지 확인한다.
     *
     * @param runId 대상 Run 식별자
     * @param timeline 검증할 Evidence 목록
     */
    private static void validateTimeline(String runId, List<EvidenceEvent> timeline) {
        long previousSequence = Long.MIN_VALUE;
        for (EvidenceEvent event : timeline) {
            if (!Objects.equals(runId, event.runId())) {
                throw new IllegalArgumentException("다른 Run의 Evidence가 Bundle snapshot에 포함됐습니다.");
            }
            if (event.sequence() <= previousSequence) {
                throw new IllegalArgumentException("Bundle Evidence sequence가 중복되거나 역순입니다.");
            }
            previousSequence = event.sequence();
        }
    }
}
