package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.outcomeprobe.foundation.OptimisticStateTransitionException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Run 상태 coordinator가 CAS 실패를 rollback 신호로 바꾸는지 검증한다.
 *
 * <p>검출 가능한 결함: Run·Step update 0건을 성공으로 무시하거나, 상태 변경 콜백의
 * 저장 예외를 삼켜 부분 commit을 허용하는 회귀.</p>
 */
class TransactionalRunStateCoordinatorTest {

    private final ScenarioRunPort runRepository = mock(ScenarioRunPort.class);
    private final RunStepPort stepRepository = mock(RunStepPort.class);
    private final TransactionalRunStateCoordinator coordinator =
            new TransactionalRunStateCoordinator(runRepository, stepRepository);

    /** Run CAS 0건은 명시적 충돌 예외로 전파돼야 한다. */
    @Test
    void runCasFailureIsNotIgnored() {
        ScenarioRun run = mock(ScenarioRun.class);
        when(run.id()).thenReturn("run-1");
        when(runRepository.update(run)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.updateRun(run))
                .isInstanceOf(OptimisticStateTransitionException.class)
                .hasMessageContaining("run-1");
    }

    /** Step CAS 0건은 명시적 충돌 예외로 전파돼야 한다. */
    @Test
    void stepCasFailureIsNotIgnored() {
        RunStepExecution step = mock(RunStepExecution.class);
        when(step.id()).thenReturn("step-row-1");
        when(stepRepository.update(step)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.updateStep(step))
                .isInstanceOf(OptimisticStateTransitionException.class)
                .hasMessageContaining("step-row-1");
    }

    /** 저장 콜백의 중간 실패를 성공으로 바꾸지 않고 transaction interceptor까지 전파해야 한다. */
    @Test
    void mutationFailureIsPropagatedForRollback() {
        IllegalStateException injected = new IllegalStateException("주입한 Evidence 저장 실패");

        assertThatThrownBy(() -> coordinator.commit(() -> {
            throw injected;
        })).isSameAs(injected);
    }

    /** deferred DB action은 Step CAS보다 먼저 실행되고 CAS 실패가 rollback 신호가 되어야 한다. */
    @Test
    void deferredPersistenceRunsBeforeFailingStepCas() {
        RunStepExecution step = mock(RunStepExecution.class);
        when(step.id()).thenReturn("step-row-1");
        when(stepRepository.update(step)).thenReturn(false);
        List<String> order = new ArrayList<>();

        assertThatThrownBy(() -> coordinator.commit(() -> {
            order.add("probe-attempt");
            coordinator.updateStep(step);
            order.add("step-state");
        })).isInstanceOf(OptimisticStateTransitionException.class);

        org.assertj.core.api.Assertions.assertThat(order)
                .containsExactly("probe-attempt");
    }
}
