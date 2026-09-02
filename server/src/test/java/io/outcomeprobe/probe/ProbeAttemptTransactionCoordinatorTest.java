package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

/**
 * Probe intent·결과·중단과 Evidence의 짧은 트랜잭션 경계를 검증한다.
 *
 * <p>검증 불변식: 시작 Evidence는 CREATED에만 한 번 기록하고, 저장 실패나 Evidence
 * 실패를 호출자에게 전파해 실제 Spring 트랜잭션이 부분 성공을 rollback할 수 있게 한다.</p>
 */
class ProbeAttemptTransactionCoordinatorTest {

    /** 신규 intent는 먼저 저장한 뒤 PROBE_ATTEMPTED Evidence를 한 번 append해야 한다. */
    @Test
    void beginAppendsAttemptedEvidenceOnlyAfterCreatedIntent() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptIntent candidate = candidateIntent();
        ProbeAttemptStart created = start(ProbeAttemptStartState.CREATED);
        AppendEvidenceCommand evidence = evidence(EvidenceType.PROBE_ATTEMPTED);
        allowRunLock(repository);
        when(repository.begin(candidate)).thenReturn(created);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        ProbeRunFence fence = fence(ProbeRunFence.Boundary.BEGIN);
        ProbeAttemptStart actual = coordinator.begin(fence, candidate, evidence);

        assertThat(actual).isSameAs(created);
        InOrder order = inOrder(repository, evidenceRecorder);
        order.verify(repository).lockRunForProbeMutation(fence);
        order.verify(repository).begin(candidate);
        order.verify(evidenceRecorder).append(evidence);
    }

    /** 기존 진행·확정 행에는 시작 Evidence를 중복 append하지 않아야 한다. */
    @ParameterizedTest
    @EnumSource(value = ProbeAttemptStartState.class,
            names = {"EXISTING_IN_FLIGHT", "EXISTING_FINALIZED"})
    void beginDoesNotAppendEvidenceForExistingIntent(ProbeAttemptStartState state) {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptIntent candidate = candidateIntent();
        ProbeAttemptStart existing = start(state);
        allowRunLock(repository);
        when(repository.begin(candidate)).thenReturn(existing);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        ProbeAttemptStart actual = coordinator.begin(
                fence(ProbeRunFence.Boundary.BEGIN), candidate,
                evidence(EvidenceType.PROBE_ATTEMPTED));

        assertThat(actual).isSameAs(existing);
        verify(evidenceRecorder, never()).append(any());
    }

    /** read-only find는 Repository 결과를 그대로 반환하고 Evidence를 만들지 않아야 한다. */
    @Test
    void findPassesThroughAuthoritativeExistingIntentWithoutEvidence() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptIntent candidate = candidateIntent();
        ProbeAttemptStart existing = start(ProbeAttemptStartState.EXISTING_IN_FLIGHT);
        when(repository.find(candidate)).thenReturn(Optional.of(existing));
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        Optional<ProbeAttemptStart> actual = coordinator.find(candidate);

        assertThat(actual).contains(existing);
        verify(repository).find(candidate);
        verify(evidenceRecorder, never()).append(any());
    }

    /** intent 저장 실패 시 Evidence를 시작하지 않고 원래 실패를 전파해야 한다. */
    @Test
    void beginPropagatesRepositoryFailureBeforeEvidence() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptIntent candidate = candidateIntent();
        allowRunLock(repository);
        when(repository.begin(candidate)).thenThrow(new IllegalStateException("insert failed"));
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.begin(
                fence(ProbeRunFence.Boundary.BEGIN), candidate,
                evidence(EvidenceType.PROBE_ATTEMPTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("insert failed");
        verify(evidenceRecorder, never()).append(any());
    }

    /** 시작 Evidence 실패를 삼키지 않아 intent INSERT rollback 신호를 보존해야 한다. */
    @Test
    void beginPropagatesEvidenceFailure() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptIntent candidate = candidateIntent();
        AppendEvidenceCommand evidence = evidence(EvidenceType.PROBE_ATTEMPTED);
        allowRunLock(repository);
        when(repository.begin(candidate)).thenReturn(start(ProbeAttemptStartState.CREATED));
        when(evidenceRecorder.append(evidence))
                .thenThrow(new IllegalStateException("evidence failed"));
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.begin(
                fence(ProbeRunFence.Boundary.BEGIN), candidate, evidence))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evidence failed");
        verify(repository).begin(candidate);
    }

    /** 완료 UPDATE 실패 시 응답·assertion Evidence를 append하지 않아야 한다. */
    @Test
    void completePropagatesRepositoryFailureBeforeEvidence() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttempt attempt = completedAttempt();
        allowRunLock(repository);
        doThrow(new IllegalStateException("conditional update failed"))
                .when(repository).complete(attempt);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.complete(
                fence(ProbeRunFence.Boundary.COMPLETE), attempt,
                List.of(evidence(EvidenceType.PROBE_RESPONSE_RECEIVED))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("conditional update failed");
        verify(evidenceRecorder, never()).append(any());
    }

    /** complete는 결과 UPDATE 뒤 응답·assertion Evidence를 전달 순서대로 append해야 한다. */
    @Test
    void completeAppendsResponseAndAssertionEvidenceAfterUpdate() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttempt attempt = completedAttempt();
        AppendEvidenceCommand response = evidence(EvidenceType.PROBE_RESPONSE_RECEIVED);
        AppendEvidenceCommand assertion = evidence(EvidenceType.PROBE_ASSERTION_PENDING);
        allowRunLock(repository);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        ProbeRunFence fence = fence(ProbeRunFence.Boundary.COMPLETE);
        coordinator.complete(fence, attempt, List.of(response, assertion));

        InOrder order = inOrder(repository, evidenceRecorder);
        order.verify(repository).lockRunForProbeMutation(fence);
        order.verify(repository).complete(attempt);
        order.verify(evidenceRecorder).append(response);
        order.verify(evidenceRecorder).append(assertion);
    }

    /** 완료 Evidence 실패를 전파해 결과 UPDATE와 같은 트랜잭션을 rollback시켜야 한다. */
    @Test
    void completePropagatesEvidenceFailure() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttempt attempt = completedAttempt();
        AppendEvidenceCommand evidence = evidence(EvidenceType.PROBE_RESPONSE_RECEIVED);
        allowRunLock(repository);
        when(evidenceRecorder.append(evidence))
                .thenThrow(new IllegalStateException("evidence failed"));
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.complete(
                fence(ProbeRunFence.Boundary.COMPLETE), attempt, List.of(evidence)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evidence failed");
        InOrder order = inOrder(repository, evidenceRecorder);
        order.verify(repository).complete(attempt);
        order.verify(evidenceRecorder).append(evidence);
    }

    /** 중단 조건부 UPDATE 0행은 Evidence 없이 예외로 fail-closed 해야 한다. */
    @Test
    void interruptRejectsMissingOrAlreadyFinalizedIntent() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptIntent intent = authoritativeIntent();
        allowRunLock(repository);
        when(repository.interrupt(intent, "PROBE_RESULT_NOT_COMMITTED")).thenReturn(false);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.interrupt(
                fence(ProbeRunFence.Boundary.ACTIVE_INTERRUPT), intent,
                evidence(EvidenceType.PROBE_ATTEMPT_INTERRUPTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("조건부 UPDATE");
        verify(evidenceRecorder, never()).append(any());
    }

    /** 중단 Evidence 실패를 전파해 PENDING 전환과 함께 rollback시켜야 한다. */
    @Test
    void interruptPropagatesEvidenceFailureAfterConditionalUpdate() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptIntent intent = authoritativeIntent();
        AppendEvidenceCommand evidence = evidence(EvidenceType.PROBE_ATTEMPT_INTERRUPTED);
        allowRunLock(repository);
        when(repository.interrupt(intent, "PROBE_RESULT_NOT_COMMITTED")).thenReturn(true);
        when(evidenceRecorder.append(evidence))
                .thenThrow(new IllegalStateException("evidence failed"));
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.interrupt(
                fence(ProbeRunFence.Boundary.ACTIVE_INTERRUPT), intent, evidence))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evidence failed");
        InOrder order = inOrder(repository, evidenceRecorder);
        order.verify(repository).interrupt(intent, "PROBE_RESULT_NOT_COMMITTED");
        order.verify(evidenceRecorder).append(evidence);
    }

    /** coordinator 의존성에는 StateProbePort 같은 외부 HTTP 경계가 없어야 한다. */
    @Test
    void hasNoHttpPortDependency() {
        boolean hasHttpPort = Arrays.stream(
                        ProbeAttemptTransactionCoordinator.class.getDeclaredFields())
                .anyMatch(field -> StateProbePort.class.isAssignableFrom(field.getType()));

        assertThat(hasHttpPort).isFalse();
    }

    /** 부모 Run fence 실패는 attempt와 Evidence를 하나도 변경하지 않아야 한다. */
    @Test
    void parentRunFenceFailureStopsMutationBeforeAttempt() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeRunFence fence = fence(ProbeRunFence.Boundary.BEGIN);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.begin(
                fence, candidateIntent(), evidence(EvidenceType.PROBE_ATTEMPTED)))
                .isInstanceOf(io.outcomeprobe.foundation.OptimisticStateTransitionException.class)
                .hasMessageContaining("부모 Run fence");

        verify(repository, never()).begin(any());
        verify(evidenceRecorder, never()).append(any());
    }

    /** 다른 Run Evidence는 부모 잠금이나 attempt 변경 전에 거부해야 한다. */
    @Test
    void mismatchedEvidenceRunIsRejectedBeforeParentLock() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        AppendEvidenceCommand mismatched = AppendEvidenceCommand.of(
                "run-other", EvidenceType.PROBE_RESPONSE_RECEIVED, EvidenceSource.PROBE);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.complete(
                fence(ProbeRunFence.Boundary.COMPLETE), completedAttempt(), List.of(mismatched)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evidence의 Run");

        verify(repository, never()).lockRunForProbeMutation(any());
        verify(repository, never()).complete(any());
    }

    /** 연산과 맞지 않는 fence 경계는 부모 잠금 전에 모두 거부해야 한다. */
    @Test
    void incompatibleOperationBoundariesAreRejectedBeforeParentLock() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        assertThatThrownBy(() -> coordinator.begin(
                fence(ProbeRunFence.Boundary.CANCEL_INTERRUPT), candidateIntent(),
                evidence(EvidenceType.PROBE_ATTEMPTED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fence 경계");
        assertThatThrownBy(() -> coordinator.complete(
                fence(ProbeRunFence.Boundary.BEGIN), completedAttempt(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fence 경계");
        assertThatThrownBy(() -> coordinator.interrupt(
                fence(ProbeRunFence.Boundary.DEADLINE_INTERRUPT), authoritativeIntent(),
                evidence(EvidenceType.PROBE_ATTEMPT_INTERRUPTED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fence 경계");
        assertThatThrownBy(() -> coordinator.interruptExisting(
                fence(ProbeRunFence.Boundary.COMPLETE), candidateIntent(), "/state/1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fence 경계");

        verify(repository, never()).lockRunForProbeMutation(any());
        verify(repository, never()).begin(any());
        verify(repository, never()).complete(any());
        verify(repository, never()).interrupt(any(), anyString());
    }

    /** complete 응답 시각과 다른 deadline 경계는 부모 잠금 전에 거부해야 한다. */
    @Test
    void completeRejectsForgedResponseBoundaryBeforeParentLock() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);
        ProbeRunFence forged = new ProbeRunFence(
                "run-1", 3L, "worker-1",
                Instant.parse("2026-08-07T01:00:10Z"),
                Instant.parse("2026-08-07T01:00:01Z"),
                Instant.EPOCH.plusSeconds(1), ProbeRunFence.Boundary.COMPLETE);

        assertThatThrownBy(() -> coordinator.complete(
                forged, completedAttempt(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("응답 시각");

        verify(repository, never()).lockRunForProbeMutation(any());
        verify(repository, never()).complete(any());
    }

    /** COMPLETE 외 경계는 transaction 시각과 다른 시각으로 deadline을 우회할 수 없다. */
    @Test
    void nonCompleteFenceRejectsDifferentBoundaryTime() {
        assertThatThrownBy(() -> new ProbeRunFence(
                "run-1", 3L, "worker-1",
                Instant.parse("2026-08-07T01:00:10Z"),
                Instant.parse("2026-08-07T01:00:01Z"),
                Instant.parse("2026-08-07T01:00:00Z"), ProbeRunFence.Boundary.BEGIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boundaryAt");
    }

    /** terminal 복구는 부모 잠금 뒤 조회하고 in-flight 행만 중단해야 한다. */
    @Test
    void interruptExistingLocksParentBeforeFindingAndInterrupting() {
        ProbeAttemptRepository repository = mock(ProbeAttemptRepository.class);
        EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
        ProbeRunFence fence = fence(ProbeRunFence.Boundary.DEADLINE_INTERRUPT);
        ProbeAttemptIntent candidate = candidateIntent();
        ProbeAttemptStart existing = start(ProbeAttemptStartState.EXISTING_IN_FLIGHT);
        allowRunLock(repository);
        when(repository.find(candidate)).thenReturn(Optional.of(existing));
        when(repository.interrupt(existing.intent(), "PROBE_RESULT_NOT_COMMITTED"))
                .thenReturn(true);
        ProbeAttemptTransactionCoordinator coordinator =
                new ProbeAttemptTransactionCoordinator(repository, evidenceRecorder);

        Optional<ProbeAttemptStart> actual = coordinator.interruptExisting(
                fence, candidate, "/state/1");

        assertThat(actual).contains(existing);
        InOrder order = inOrder(repository, evidenceRecorder);
        order.verify(repository).lockRunForProbeMutation(fence);
        order.verify(repository).find(candidate);
        order.verify(repository).interrupt(existing.intent(), "PROBE_RESULT_NOT_COMMITTED");
        order.verify(evidenceRecorder).append(any());
    }

    /** 아직 DB id를 발급받지 않은 시작 intent fixture를 만든다. */
    private ProbeAttemptIntent candidateIntent() {
        return new ProbeAttemptIntent(null, "run-1", "step-1", 1, Instant.EPOCH);
    }

    /** DB 권위 id가 반영된 시작 intent fixture를 만든다. */
    private ProbeAttemptIntent authoritativeIntent() {
        return new ProbeAttemptIntent("attempt-1", "run-1", "step-1", 1, Instant.EPOCH);
    }

    /** 지정 상태와 DB 권위 intent를 가진 시작 결과 fixture를 만든다. */
    private ProbeAttemptStart start(ProbeAttemptStartState state) {
        return new ProbeAttemptStart(authoritativeIntent(), state);
    }

    /** 원문 body가 없는 완료 Probe attempt fixture를 만든다. */
    private ProbeAttempt completedAttempt() {
        return new ProbeAttempt("attempt-1", "run-1", "step-1", 1,
                Instant.EPOCH, Instant.EPOCH, 200, null, List.of(),
                ProbeDecision.PENDING, null);
    }

    /** coordinator에 전달할 최소 Evidence 명령을 만든다. */
    private AppendEvidenceCommand evidence(EvidenceType type) {
        return AppendEvidenceCommand.of("run-1", type, EvidenceSource.PROBE);
    }

    /** 테스트할 Probe mutation에 부모 Run 잠금을 허용한다. */
    private void allowRunLock(ProbeAttemptRepository repository) {
        when(repository.lockRunForProbeMutation(any())).thenReturn(true);
    }

    /** 지정 경계의 유효한 Run lease fence를 만든다. */
    private ProbeRunFence fence(ProbeRunFence.Boundary boundary) {
        Instant fencedAt = Instant.parse("2026-08-07T01:00:01Z");
        Instant boundaryAt = boundary == ProbeRunFence.Boundary.COMPLETE
                ? Instant.EPOCH : fencedAt;
        return new ProbeRunFence("run-1", 3L, "worker-1",
                Instant.parse("2026-08-07T01:00:10Z"),
                fencedAt, boundaryAt, boundary);
    }
}
