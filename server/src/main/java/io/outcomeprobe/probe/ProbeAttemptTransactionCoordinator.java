package io.outcomeprobe.probe;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.foundation.OptimisticStateTransitionException;
import io.outcomeprobe.foundation.PostgresqlTimestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Probe HTTP 전 intent와 HTTP 후 결과를 각각 짧은 트랜잭션으로 기록한다.
 *
 * <p>책임: intent·시작 Evidence, 결과·응답 Evidence, 중단 상태·중단 Evidence가
 * 각각 부분 성공하지 않도록 트랜잭션 경계를 제공한다.</p>
 * <p>핵심 불변식: 모든 쓰기는 부모 {@code scenario_run}을 먼저 잠근 뒤
 * {@code probe_attempt}와 Evidence를 변경한다. 이 coordinator는 HTTP 포트를 의존하지도
 * 호출하지도 않으며, 외부 HTTP는 begin transaction이 끝난 뒤에만 실행돼야 한다.</p>
 */
@Service
public class ProbeAttemptTransactionCoordinator {

    private static final String RESULT_NOT_COMMITTED = "PROBE_RESULT_NOT_COMMITTED";

    private final ProbeAttemptRepository attemptRepository;
    private final EvidenceRecorder evidenceRecorder;

    /**
     * Probe intent·결과·중단 저장에 필요한 영속 포트만 주입한다.
     *
     * @param attemptRepository attempt 저장 포트
     * @param evidenceRecorder append-only Evidence 기록 포트
     */
    public ProbeAttemptTransactionCoordinator(ProbeAttemptRepository attemptRepository,
                                              EvidenceRecorder evidenceRecorder) {
        this.attemptRepository = attemptRepository;
        this.evidenceRecorder = evidenceRecorder;
    }

    /**
     * HTTP 전 intent를 시작하고 신규 생성일 때만 PROBE_ATTEMPTED Evidence를 기록한다.
     *
     * <p>unique 충돌로 찾은 기존 행에는 시작 Evidence를 다시 append하지 않는다.
     * 저장 또는 Evidence 실패는 그대로 전파돼 같은 트랜잭션을 rollback시킨다.</p>
     *
     * @param fence 부모 Run lease와 시작 경계
     * @param intent 시작할 Probe attempt 의도
     * @param attemptedEvidence HTTP 시도 시작을 설명하는 Evidence
     * @return DB 권위 intent와 신규·기존 상태
     */
    @Transactional
    public ProbeAttemptStart begin(ProbeRunFence fence,
                                   ProbeAttemptIntent intent,
                                   AppendEvidenceCommand attemptedEvidence) {
        requireBoundary(fence, ProbeRunFence.Boundary.BEGIN);
        requireSameRun(fence, intent.runId(), List.of(attemptedEvidence));
        requireRunLock(fence);
        ProbeAttemptStart start = attemptRepository.begin(intent);
        if (start.state() == ProbeAttemptStartState.CREATED) {
            evidenceRecorder.append(attemptedEvidence);
        }
        return start;
    }

    /**
     * 새 intent를 만들지 않고 같은 identity의 기존 Probe attempt를 조회한다.
     *
     * <p>deadline 종료 판단과 기존 in-flight 중단이 Run 상태 전이 트랜잭션 안에서
     * 함께 실행될 수 있도록 read-only 조회 경계를 제공한다.</p>
     *
     * @param candidate 조회할 Run·Step·attempt identity 후보
     * @return 기존 권위 intent와 상태, 존재하지 않으면 empty
     */
    @Transactional(readOnly = true)
    public Optional<ProbeAttemptStart> find(ProbeAttemptIntent candidate) {
        return attemptRepository.find(candidate);
    }

    /**
     * HTTP 결과와 응답·assertion Evidence를 한 트랜잭션에서 확정한다.
     *
     * <p>conditional UPDATE 또는 Evidence 하나라도 실패하면 예외를 삼키지 않아
     * 결과와 Timeline이 함께 rollback되게 한다.</p>
     *
     * @param fence 부모 Run lease와 결과 확정 경계
     * @param attempt 외부 HTTP 관측과 판정을 담은 완료 attempt
     * @param evidenceCommands 응답과 assertion을 설명하는 Evidence 명령
     */
    @Transactional
    public void complete(ProbeRunFence fence,
                         ProbeAttempt attempt,
                         List<AppendEvidenceCommand> evidenceCommands) {
        requireBoundary(fence, ProbeRunFence.Boundary.COMPLETE);
        List<AppendEvidenceCommand> commands = List.copyOf(evidenceCommands);
        requireSameRun(fence, attempt.runId(), commands);
        if (!fence.boundaryAt().equals(
                PostgresqlTimestamp.normalize(attempt.respondedAt()))) {
            throw new IllegalArgumentException(
                    "Probe complete 응답 시각과 Run fence 경계 시각이 일치하지 않습니다.");
        }
        requireRunLock(fence);
        attemptRepository.complete(attempt);
        commands.forEach(evidenceRecorder::append);
    }

    /**
     * HTTP 결과를 확정하지 못한 intent와 중단 Evidence를 한 트랜잭션에서 기록한다.
     *
     * <p>이미 완료·중단된 행처럼 조건부 UPDATE가 0행이면 Evidence를 기록하지 않고
     * 예외로 실패해 중복 복구를 성공처럼 보이지 않게 한다.</p>
     *
     * @param fence 부모 Run lease와 중단 경계
     * @param intent 중단할 DB 권위 intent
     * @param interruptedEvidence 결과 미확정을 설명하는 Evidence
     */
    @Transactional
    public void interrupt(ProbeRunFence fence,
                          ProbeAttemptIntent intent,
                          AppendEvidenceCommand interruptedEvidence) {
        requireBoundary(fence, ProbeRunFence.Boundary.ACTIVE_INTERRUPT);
        requireSameRun(fence, intent.runId(), List.of(interruptedEvidence));
        requireRunLock(fence);
        interruptAfterRunLock(intent, interruptedEvidence);
    }

    /**
     * 부모 Run을 먼저 잠근 뒤 기존 in-flight attempt를 찾아 중단한다.
     *
     * <p>terminal Worker가 lock 전 plain SELECT로 미커밋 begin을 놓치지 않도록 부모 잠금,
     * 조회, 조건부 중단, Evidence를 하나의 transaction 순서로 고정한다.</p>
     *
     * @param fence 부모 Run lease와 중단 경계
     * @param candidate 조회할 Run·Step·attempt identity 후보
     * @param path 중단 Evidence에 기록할 안전한 상대 경로
     * @return 잠금 뒤 확인한 기존 attempt, 없으면 empty
     */
    @Transactional
    public Optional<ProbeAttemptStart> interruptExisting(
            ProbeRunFence fence, ProbeAttemptIntent candidate, String path) {
        requireBoundary(fence,
                ProbeRunFence.Boundary.ACTIVE_INTERRUPT,
                ProbeRunFence.Boundary.CANCEL_INTERRUPT,
                ProbeRunFence.Boundary.DEADLINE_INTERRUPT);
        requireSameRun(fence, candidate.runId(), List.of());
        requireRunLock(fence);
        Optional<ProbeAttemptStart> existing = attemptRepository.find(candidate);
        existing.filter(value -> value.state() == ProbeAttemptStartState.EXISTING_IN_FLIGHT)
                .ifPresent(value -> interruptAfterRunLock(value.intent(),
                        ProbeAttemptEvidenceFactory.interrupted(value.intent(), path)));
        return existing;
    }

    /** 부모 Run 잠금 뒤에만 attempt와 Evidence를 중단 상태로 확정한다. */
    private void interruptAfterRunLock(ProbeAttemptIntent intent,
                                       AppendEvidenceCommand interruptedEvidence) {
        if (!attemptRepository.interrupt(intent, RESULT_NOT_COMMITTED)) {
            throw new IllegalStateException(
                    "Probe attempt 중단 조건부 UPDATE가 적용되지 않았습니다: " + intent.id());
        }
        evidenceRecorder.append(interruptedEvidence);
    }

    /** 부모 Run 선잠금 실패를 lease 상실로 승격해 자식 쓰기를 막는다. */
    private void requireRunLock(ProbeRunFence fence) {
        if (!attemptRepository.lockRunForProbeMutation(fence)) {
            throw new OptimisticStateTransitionException(
                    "Probe 부모 Run fence가 상태 변경을 거부했습니다: " + fence.runId()
                            + " (" + fence.boundary() + ")");
        }
    }

    /** 호출 연산과 fence 경계의 조합을 부모 잠금 전에 제한한다. */
    private void requireBoundary(ProbeRunFence fence, ProbeRunFence.Boundary... allowed) {
        for (ProbeRunFence.Boundary candidate : allowed) {
            if (fence.boundary() == candidate) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Probe mutation과 Run fence 경계가 일치하지 않습니다: " + fence.boundary());
    }

    /** attempt와 모든 Evidence가 잠근 부모 Run 하나에만 속하는지 먼저 검증한다. */
    private void requireSameRun(ProbeRunFence fence, String mutationRunId,
                                List<AppendEvidenceCommand> evidenceCommands) {
        if (!fence.runId().equals(mutationRunId)) {
            throw new IllegalArgumentException("Probe mutation의 Run과 fence가 일치하지 않습니다.");
        }
        boolean mismatchedEvidence = evidenceCommands.stream()
                .anyMatch(command -> !fence.runId().equals(command.runId()));
        if (mismatchedEvidence) {
            throw new IllegalArgumentException("Probe Evidence의 Run과 fence가 일치하지 않습니다.");
        }
    }
}
