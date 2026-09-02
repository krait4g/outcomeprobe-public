package io.outcomeprobe.execution.step;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.emulator.ProviderResource;
import io.outcomeprobe.emulator.ProviderStatePort;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.evidence.EvidenceMatcher;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * provider.assert Step의 Provider truth 판정을 검증한다.
 *
 * <p>검출 가능한 결함: Provider canonical 상태나 commit 횟수가 기대와 다른데
 * 통과로 판정하는 문제. 상태·횟수 불일치 시 Failed여야 한다.</p>
 */
class ProviderAssertStepExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 고정된 resource를 반환하는 ProviderStatePort stub이다.
     */
    private static final class StubStatePort implements ProviderStatePort {
        private final ProviderResource resource;

        StubStatePort(ProviderResource resource) {
            this.resource = resource;
        }

        @Override
        /**
         * seed는 사용하지 않는다.
         */
        public void seed(ProviderResource resource) {
        }

        /**
         * 전이는 사용하지 않으며 항상 성공을 반환한다.
         */
        public boolean commitTransition(String runId, String resourceType, String resourceId,
                                        long expectedVersion, String newCanonicalState,
                                        String newVisibleState, int remainingStaleQueryAttempts,
                                        java.time.Instant visibleAfter,
                                        Map<String, Object> attributes) {
            return true;
        }

        @Override
        /**
         * 고정된 resource를 반환한다.
         */
        public Optional<ProviderResource> find(String runId, String type, String id) {
            return Optional.ofNullable(resource);
        }

        @Override
        /**
         * 고정된 resource를 반환한다.
         */
        public Optional<ProviderResource> consumeQuery(String runId, String type, String id) {
            return Optional.ofNullable(resource);
        }
    }

    /**
     * 고정 commit 횟수를 반환하는 EvidenceQueryPort stub이다.
     */
    private static final class StubQueryPort implements EvidenceQueryPort {
        private final long commitCount;

        StubQueryPort(long commitCount) {
            this.commitCount = commitCount;
        }

        @Override
        /**
         * 고정 commit 횟수를 반환한다.
         */
        public long count(String runId, EvidenceMatcher matcher) {
            return commitCount;
        }

        @Override
        /**
         * commit 횟수가 0보다 큰지 반환한다.
         */
        public boolean exists(String runId, EvidenceMatcher matcher) {
            return commitCount > 0;
        }

        @Override
        /**
         * 이 stub을 사용하는 provider.assert에는 시간 경계 조회가 없으므로 기존 존재 판정을 반환한다.
         */
        public boolean existsObservedAtOrBefore(String runId, EvidenceMatcher matcher,
                                                Instant observedAtInclusive) {
            return commitCount > 0;
        }

        @Override
        /**
         * commit이 있으면 sequence 1을 반환한다.
         */
        public Optional<Long> firstSequenceOf(String runId, EvidenceMatcher matcher) {
            return commitCount > 0 ? Optional.of(1L) : Optional.empty();
        }

        @Override
        /**
         * 빈 Timeline을 반환한다.
         */
        public List<EvidenceEvent> timeline(String runId) {
            return List.of();
        }
    }

    /**
     * 기록을 무시하는 EvidenceRecorder stub이다.
     */
    private static final class SilentRecorder implements EvidenceRecorder {
        @Override
        /**
         * 기록하지 않고 더미 사건을 반환한다.
         */
        public EvidenceEvent append(AppendEvidenceCommand command) {
            return new EvidenceEvent("e", command.runId(), 1L, null, Instant.now(),
                    command.type(), command.source(), command.stepId(), null, null, null,
                    command.attributes(), null, null, false, Instant.now());
        }
    }

    /**
     * 지정한 상태·version의 테스트용 resource를 만든다.
     */
    private ProviderResource resource(String state, long version) {
        return new ProviderResource("r-1", "run-1", "payment", "pay-1", state, state,
                version, 0, null, Map.of(), Instant.now());
    }

    /**
     * provider.assert Step 실행 문맥을 조립한다.
     */
    private StepExecutionContext context(String detailJson) throws Exception {
        ScenarioRun run = new ScenarioRun("run-1", "s", "1.0.0", "h", "target", 1L,
                RunStatus.RUNNING, null, null, Instant.now(), Instant.now().plusSeconds(60),
                null, null, 0, 0, false, null, null, Instant.now(), null, Instant.now());
        CompiledRunPlan plan = new CompiledRunPlan("run-1", "s", "1.0.0", "h", "c", "1.0.0",
                "alias", "target", 1L, 60_000L, Instant.now(), Map.of(), List.of());
        JsonNode detail = mapper.readTree(detailJson);
        RunStepExecution step = new RunStepExecution("step-1", "run-1", "provider-assert", 0,
                StepType.PROVIDER_ASSERT, io.outcomeprobe.execution.StepStatus.RUNNING, detail, null,
                null, Instant.now(), Instant.now().plusSeconds(60), 0, null, null, 0, Instant.now(), null);
        return new StepExecutionContext(run, plan, step);
    }

    /**
     * Provider 상태가 기대와 일치하면 Completed여야 한다.
     *
     * @throws Exception JSON 해석 실패 시 발생한다
     */
    @Test
    void passesWhenStateMatches() throws Exception {
        ProviderAssertStepExecutor executor = new ProviderAssertStepExecutor(
                new StubStatePort(resource("SUCCEEDED", 2)), new StubQueryPort(1), new SilentRecorder());

        String detail = "{\"assertions\":[{\"resource\":{\"type\":\"payment\",\"id\":\"pay-1\"},\"state\":\"SUCCEEDED\"}]}";
        StepExecutionResult result = executor.execute(context(detail));

        assertThat(result).isInstanceOf(StepExecutionResult.Completed.class);
    }

    /**
     * Provider 상태가 기대와 다르면 Failed여야 한다.
     *
     * @throws Exception JSON 해석 실패 시 발생한다
     */
    @Test
    void failsWhenStateMismatch() throws Exception {
        ProviderAssertStepExecutor executor = new ProviderAssertStepExecutor(
                new StubStatePort(resource("FAILED", 1)), new StubQueryPort(1), new SilentRecorder());

        String detail = "{\"assertions\":[{\"resource\":{\"type\":\"payment\",\"id\":\"pay-1\"},\"state\":\"SUCCEEDED\"}]}";
        StepExecutionResult result = executor.execute(context(detail));

        assertThat(result).isInstanceOf(StepExecutionResult.Failed.class);
    }

    /**
     * commit 횟수가 기대와 다르면 Failed여야 한다.
     *
     * @throws Exception JSON 해석 실패 시 발생한다
     */
    @Test
    void failsWhenCommitCountMismatch() throws Exception {
        ProviderAssertStepExecutor executor = new ProviderAssertStepExecutor(
                new StubStatePort(resource("SUCCEEDED", 2)), new StubQueryPort(2), new SilentRecorder());

        String detail = "{\"assertions\":[{\"operation\":\"confirmPayment\",\"count\":1}]}";
        StepExecutionResult result = executor.execute(context(detail));

        assertThat(result).isInstanceOf(StepExecutionResult.Failed.class);
    }
}
