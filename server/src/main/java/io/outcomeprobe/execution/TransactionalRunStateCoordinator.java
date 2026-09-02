package io.outcomeprobe.execution;

import java.util.function.Supplier;
import io.outcomeprobe.foundation.OptimisticStateTransitionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RunEngine의 순수 영속 상태 변경을 하나의 DB 트랜잭션으로 감싸는 coordinator다.
 *
 * <p>책임: Step·Run·Evidence·alias 변경이 함께 성공하거나 함께 rollback되도록 별도
 * Spring proxy 경계를 제공한다. 핵심 불변식은 외부 HTTP 실행기가 이 클래스의
 * 의존성이나 콜백 안에 들어오지 않는 것이며, 호출자는 HTTP 결과를 받은 뒤에만
 * 상태 변경 콜백을 전달해야 한다.</p>
 */
@Service
public class TransactionalRunStateCoordinator {

    private final ScenarioRunPort runRepository;
    private final RunStepPort stepRepository;

    /**
     * CAS가 필요한 Run·Step 저장 포트를 주입한다.
     *
     * @param runRepository Run 상태 저장 포트
     * @param stepRepository Step 상태 저장 포트
     */
    public TransactionalRunStateCoordinator(ScenarioRunPort runRepository,
                                            RunStepPort stepRepository) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
    }

    /**
     * 반환값이 없는 Run 상태 변경 단위를 새 트랜잭션 경계에서 실행한다.
     *
     * <p>콜백 예외는 잡지 않아 CAS 실패와 저장 실패가 전체 변경을 rollback시킨다.</p>
     *
     * @param mutation 외부 HTTP를 포함하지 않는 영속 상태 변경
     */
    @Transactional
    public void commit(Runnable mutation) {
        mutation.run();
    }

    /**
     * 결과를 반환하는 Run 상태 변경 단위를 새 트랜잭션 경계에서 실행한다.
     *
     * <p>prepare의 alias 획득 결과처럼 commit 뒤 호출자가 알아야 하는 값을 보존한다.</p>
     *
     * @param mutation 외부 HTTP를 포함하지 않는 영속 상태 변경
     * @param <T> 상태 변경 결과 타입
     * @return 트랜잭션 안에서 계산한 결과
     */
    @Transactional
    public <T> T commit(Supplier<T> mutation) {
        return mutation.get();
    }

    /**
     * Run version CAS를 실행하고 0건 갱신을 트랜잭션 실패로 바꾼다.
     *
     * @param run 갱신할 Run snapshot
     * @throws OptimisticStateTransitionException lease·version을 잃었을 때 발생한다
     */
    public void updateRun(ScenarioRun run) {
        // CAS 실패 예외가 같은 commit 콜백의 Step·Evidence·alias 변경을 rollback시킨다.
        if (!runRepository.update(run)) {
            throw new OptimisticStateTransitionException(
                    "Run CAS에 실패했습니다: " + run.id());
        }
    }

    /**
     * Step version CAS를 실행하고 0건 갱신을 트랜잭션 실패로 바꾼다.
     *
     * @param step 갱신할 Step snapshot
     * @throws OptimisticStateTransitionException lease·version을 잃었을 때 발생한다
     */
    public void updateStep(RunStepExecution step) {
        // Step CAS 실패 뒤 Run과 Evidence가 따로 확정되지 않도록 예외를 즉시 전파한다.
        if (!stepRepository.update(step)) {
            throw new OptimisticStateTransitionException(
                    "Run Step CAS에 실패했습니다: " + step.id());
        }
    }
}
