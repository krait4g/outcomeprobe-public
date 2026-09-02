package io.outcomeprobe.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.OptimisticStateTransitionException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Delivery 결과와 Evidence의 실패 전파·CAS 불변식을 검증한다.
 *
 * <p>검출 가능한 결함: lease를 잃은 Worker가 성공 Evidence를 남기거나 Evidence 저장
 * 실패를 삼켜 delivery 상태만 commit하는 회귀.</p>
 */
class DeliveryResultTransactionCoordinatorTest {

    private static final String RECOVERY_RUN_ID = "run-recovery";

    private final CallbackDeliveryRepository repository = mock(CallbackDeliveryRepository.class);
    private final EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
    private final DeliveryResultTransactionCoordinator coordinator =
            new DeliveryResultTransactionCoordinator(repository, evidenceRecorder);

    /** 각 테스트의 정상 경로에서 부모 Run 잠금을 기본 허용한다. */
    @BeforeEach
    void allowParentRunLock() {
        when(repository.lockRunForDeliveryMutation(any())).thenReturn(true);
        when(repository.lockNextRunForExpiredRecovery(any()))
                .thenReturn(Optional.of(RECOVERY_RUN_ID));
    }

    /** 실제 전송 결과를 확정할 수 없는 delivery도 다시 lease되지 않는 최종 상태다. */
    @Test
    void unknownDeliveryStatusIsFinal() {
        assertThat(DeliveryStatus.UNKNOWN.isFinal()).isTrue();
    }

    /** 전송 권한 CAS 성공과 ATTEMPTED Evidence는 같은 coordinator 경계에서 확정한다. */
    @Test
    void beginAttemptAppendsEvidenceOnlyAfterSuccessfulFence() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery leased = mock(CallbackDelivery.class);
        when(leased.runId()).thenReturn("run-1");
        CallbackDelivery attempting = mock(CallbackDelivery.class);
        AppendEvidenceCommand evidence = AppendEvidenceCommand.of(
                "run-1", EvidenceType.CALLBACK_DELIVERY_ATTEMPTED, EvidenceSource.DELIVERY);
        when(repository.beginAttempt(leased, now, 10_000L))
                .thenReturn(java.util.Optional.of(attempting));

        java.util.Optional<CallbackDelivery> result =
                coordinator.beginAttempt(leased, now, 10_000L, evidence);

        assertThat(result).containsSame(attempting);
        verify(evidenceRecorder).append(evidence);
        InOrder lockOrder = inOrder(repository);
        lockOrder.verify(repository).lockRunForDeliveryMutation("run-1");
        lockOrder.verify(repository).beginAttempt(leased, now, 10_000L);
    }

    /** 전송 권한 CAS 실패는 ATTEMPTED Evidence와 외부 전송의 선행 허가가 될 수 없다. */
    @Test
    void beginAttemptFenceFailureLeavesNoEvidence() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery leased = mock(CallbackDelivery.class);
        when(leased.runId()).thenReturn("run-1");
        AppendEvidenceCommand evidence = AppendEvidenceCommand.of(
                "run-1", EvidenceType.CALLBACK_DELIVERY_ATTEMPTED, EvidenceSource.DELIVERY);
        when(repository.beginAttempt(leased, now, 10_000L))
                .thenReturn(java.util.Optional.empty());

        assertThat(coordinator.beginAttempt(leased, now, 10_000L, evidence)).isEmpty();

        verify(evidenceRecorder, never()).append(evidence);
    }

    /** ATTEMPTED Evidence 저장 실패는 fence 상태만 단독 commit되지 않게 전파해야 한다. */
    @Test
    void beginAttemptEvidenceFailureIsPropagated() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery leased = mock(CallbackDelivery.class);
        when(leased.runId()).thenReturn("run-1");
        CallbackDelivery attempting = mock(CallbackDelivery.class);
        AppendEvidenceCommand evidence = AppendEvidenceCommand.of(
                "run-1", EvidenceType.CALLBACK_DELIVERY_ATTEMPTED, EvidenceSource.DELIVERY);
        when(repository.beginAttempt(leased, now, 10_000L))
                .thenReturn(java.util.Optional.of(attempting));
        IllegalStateException injected = new IllegalStateException("주입한 ATTEMPTED Evidence 실패");
        when(evidenceRecorder.append(evidence)).thenThrow(injected);

        assertThatThrownBy(() -> coordinator.beginAttempt(leased, now, 10_000L, evidence))
                .isSameAs(injected);
    }

    /** delivery CAS 실패 시 Evidence를 한 건도 append하지 않아야 한다. */
    @Test
    void casFailureStopsEvidenceAppend() {
        CallbackDelivery delivery = mock(CallbackDelivery.class);
        when(delivery.id()).thenReturn("delivery-1");
        when(delivery.runId()).thenReturn("run-1");
        when(repository.update(delivery)).thenReturn(false);
        AppendEvidenceCommand evidence = evidence();

        assertThatThrownBy(() -> coordinator.commit(delivery, List.of(evidence)))
                .isInstanceOf(OptimisticStateTransitionException.class)
                .hasMessageContaining("delivery-1");
        verify(evidenceRecorder, never()).append(evidence);
        InOrder lockOrder = inOrder(repository);
        lockOrder.verify(repository).lockRunForDeliveryMutation("run-1");
        lockOrder.verify(repository).update(delivery);
    }

    /** terminal 부모에서 RETRY_WAIT fence가 거부되면 재시도 Evidence도 남기지 않아야 한다. */
    @Test
    void rejectedRetryWaitLeavesAttemptUnresolvedWithoutRetryEvidence() {
        CallbackDelivery delivery = mock(CallbackDelivery.class);
        when(delivery.id()).thenReturn("delivery-retry");
        when(delivery.runId()).thenReturn("run-terminal");
        when(delivery.status()).thenReturn(DeliveryStatus.RETRY_WAIT);
        when(repository.update(delivery)).thenReturn(false);
        AppendEvidenceCommand evidence = AppendEvidenceCommand.of(
                "run-terminal", EvidenceType.CALLBACK_DELIVERY_RETRY_SCHEDULED,
                EvidenceSource.DELIVERY);

        assertThatThrownBy(() -> coordinator.commit(delivery, List.of(evidence)))
                .isInstanceOf(OptimisticStateTransitionException.class)
                .hasMessageContaining("RETRY_WAIT")
                .hasMessageContaining("delivery-retry");

        verify(evidenceRecorder, never()).append(evidence);
        InOrder lockOrder = inOrder(repository);
        lockOrder.verify(repository).lockRunForDeliveryMutation("run-terminal");
        lockOrder.verify(repository).update(delivery);
    }

    /** 저장소 fence가 허용한 실제 최종 HTTP 결과는 terminal 부모에서도 그대로 확정한다. */
    @Test
    void authoritativeSucceededAndDeadResultsRemainCommittable() {
        CallbackDelivery succeeded = mock(CallbackDelivery.class);
        when(succeeded.runId()).thenReturn("run-succeeded");
        when(succeeded.status()).thenReturn(DeliveryStatus.SUCCEEDED);
        CallbackDelivery dead = mock(CallbackDelivery.class);
        when(dead.runId()).thenReturn("run-dead");
        when(dead.status()).thenReturn(DeliveryStatus.DEAD);
        when(repository.update(succeeded)).thenReturn(true);
        when(repository.update(dead)).thenReturn(true);
        AppendEvidenceCommand succeededEvidence = AppendEvidenceCommand.of(
                "run-succeeded", EvidenceType.CALLBACK_DELIVERY_SUCCEEDED,
                EvidenceSource.DELIVERY);
        AppendEvidenceCommand deadEvidence = AppendEvidenceCommand.of(
                "run-dead", EvidenceType.CALLBACK_DELIVERY_DEAD, EvidenceSource.DELIVERY);

        coordinator.commit(succeeded, List.of(succeededEvidence));
        coordinator.commit(dead, List.of(deadEvidence));

        verify(evidenceRecorder).append(succeededEvidence);
        verify(evidenceRecorder).append(deadEvidence);
        InOrder lockOrder = inOrder(repository);
        lockOrder.verify(repository).lockRunForDeliveryMutation("run-succeeded");
        lockOrder.verify(repository).update(succeeded);
        lockOrder.verify(repository).lockRunForDeliveryMutation("run-dead");
        lockOrder.verify(repository).update(dead);
    }

    /** 부모 Run 잠금 실패는 delivery 결과 쓰기와 Evidence보다 먼저 중단돼야 한다. */
    @Test
    void missingParentRunStopsDeliveryMutation() {
        CallbackDelivery delivery = mock(CallbackDelivery.class);
        when(delivery.runId()).thenReturn("run-missing");
        when(repository.lockRunForDeliveryMutation("run-missing")).thenReturn(false);
        AppendEvidenceCommand evidence = evidence();

        assertThatThrownBy(() -> coordinator.commit(delivery, List.of(evidence)))
                .isInstanceOf(OptimisticStateTransitionException.class)
                .hasMessageContaining("run-missing");

        verify(repository, never()).update(delivery);
        verify(evidenceRecorder, never()).append(evidence);
    }

    /** Evidence 저장 실패가 호출자까지 전파돼 상위 트랜잭션 rollback을 유도해야 한다. */
    @Test
    void evidenceFailureIsPropagatedAfterSuccessfulCas() {
        CallbackDelivery delivery = mock(CallbackDelivery.class);
        when(delivery.runId()).thenReturn("run-1");
        when(repository.update(delivery)).thenReturn(true);
        AppendEvidenceCommand evidence = evidence();
        IllegalStateException injected = new IllegalStateException("주입한 append 실패");
        when(evidenceRecorder.append(evidence)).thenThrow(injected);

        assertThatThrownBy(() -> coordinator.commit(delivery, List.of(evidence)))
                .isSameAs(injected);
    }

    /** terminal 만료 lease는 UNKNOWN 상태와 Evidence를 같은 호출 경계에서 확정해야 한다. */
    @Test
    void expiredTerminalLeaseCreatesUnknownEvidence() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery delivery = mock(CallbackDelivery.class);
        when(delivery.id()).thenReturn("delivery-unknown");
        when(delivery.runId()).thenReturn(RECOVERY_RUN_ID);
        when(delivery.occurrence()).thenReturn(2);
        when(delivery.lastFailureCode())
                .thenReturn("DELIVERY_OUTCOME_UNKNOWN_AFTER_RUN_TERMINATION");
        when(repository.markExpiredForTerminalRunUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of(delivery));
        when(repository.markExpiredExhaustedAttemptsUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of());

        int recovered = coordinator.recoverExpiredUncertainDeliveries(now);

        assertThat(recovered).isEqualTo(1);
        ArgumentCaptor<AppendEvidenceCommand> evidence =
                ArgumentCaptor.forClass(AppendEvidenceCommand.class);
        verify(evidenceRecorder).append(evidence.capture());
        assertThat(evidence.getValue().type())
                .isEqualTo(EvidenceType.CALLBACK_DELIVERY_OUTCOME_UNKNOWN);
        assertThat(evidence.getValue().attributes())
                .containsEntry("deliveryId", "delivery-unknown")
                .containsEntry("occurrence", "2")
                .containsEntry("transmissionState", "UNKNOWN")
                .containsEntry("reason", "DELIVERY_OUTCOME_UNKNOWN_AFTER_RUN_TERMINATION");
        assertThat(evidence.getValue().observedAt()).isEqualTo(now);
    }

    /** 여러 만료 행의 반환 순서와 무관하게 Evidence sequence는 항상 결정적이어야 한다. */
    @Test
    void expiredTerminalLeaseEvidenceUsesDeterministicOrder() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery last = unknownDelivery("delivery-z", RECOVERY_RUN_ID, 3);
        CallbackDelivery middle = unknownDelivery("delivery-m", RECOVERY_RUN_ID, 2);
        CallbackDelivery first = unknownDelivery("delivery-a", RECOVERY_RUN_ID, 1);
        when(repository.markExpiredForTerminalRunUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of(last, middle, first));
        when(repository.markExpiredExhaustedAttemptsUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of());

        assertThat(coordinator.recoverExpiredUncertainDeliveries(now)).isEqualTo(3);

        ArgumentCaptor<AppendEvidenceCommand> evidence =
                ArgumentCaptor.forClass(AppendEvidenceCommand.class);
        verify(evidenceRecorder, org.mockito.Mockito.times(3)).append(evidence.capture());
        assertThat(evidence.getAllValues())
                .extracting(command -> command.attributes().get("deliveryId"))
                .containsExactly("delivery-a", "delivery-m", "delivery-z");
        InOrder recoveryOrder = inOrder(repository);
        recoveryOrder.verify(repository).lockNextRunForExpiredRecovery(now);
        recoveryOrder.verify(repository)
                .cancelStrandedPendingForTerminalRun(RECOVERY_RUN_ID, now);
        recoveryOrder.verify(repository)
                .markExpiredForTerminalRunUnknown(RECOVERY_RUN_ID, now);
        recoveryOrder.verify(repository)
                .markExpiredExhaustedAttemptsUnknown(RECOVERY_RUN_ID, now);
    }

    /** UNKNOWN Evidence 실패도 호출자에게 전파돼 상태 갱신 rollback을 유도해야 한다. */
    @Test
    void expiredTerminalLeaseEvidenceFailureIsPropagated() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery delivery = mock(CallbackDelivery.class);
        when(delivery.id()).thenReturn("delivery-unknown");
        when(delivery.runId()).thenReturn("run-terminal");
        when(delivery.lastFailureCode()).thenReturn("OUTCOME_UNKNOWN");
        when(repository.markExpiredForTerminalRunUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of(delivery));
        when(repository.markExpiredExhaustedAttemptsUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of());
        IllegalStateException injected = new IllegalStateException("주입한 UNKNOWN Evidence 실패");
        when(evidenceRecorder.append(any(AppendEvidenceCommand.class))).thenThrow(injected);

        assertThatThrownBy(() -> coordinator.recoverExpiredUncertainDeliveries(now))
                .isSameAs(injected);
    }

    /** 재시도 상한을 소비한 crash delivery도 같은 UNKNOWN Evidence 경계로 복구한다. */
    @Test
    void exhaustedAttemptCreatesUnknownEvidence() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        CallbackDelivery delivery = unknownDelivery("delivery-limit", RECOVERY_RUN_ID, 3);
        when(delivery.lastFailureCode())
                .thenReturn("DELIVERY_OUTCOME_UNKNOWN_AFTER_ATTEMPT_LIMIT");
        when(repository.markExpiredExhaustedAttemptsUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of(delivery));
        when(repository.markExpiredForTerminalRunUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of());

        assertThat(coordinator.recoverExpiredUncertainDeliveries(now)).isEqualTo(1);

        ArgumentCaptor<AppendEvidenceCommand> evidence =
                ArgumentCaptor.forClass(AppendEvidenceCommand.class);
        verify(evidenceRecorder).append(evidence.capture());
        assertThat(evidence.getValue().attributes())
                .containsEntry("deliveryId", "delivery-limit")
                .containsEntry("occurrence", "3")
                .containsEntry("reason", "DELIVERY_OUTCOME_UNKNOWN_AFTER_ATTEMPT_LIMIT");
    }

    /** 후보 부모를 잠그지 못한 tick은 어떤 delivery UPDATE도 실행하지 않아야 한다. */
    @Test
    void noLockedRecoveryParentSkipsEveryDeliveryMutation() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        when(repository.lockNextRunForExpiredRecovery(now)).thenReturn(Optional.empty());

        assertThat(coordinator.recoverExpiredUncertainDeliveries(now)).isZero();

        verify(repository, never())
                .cancelStrandedPendingForTerminalRun(any(), any());
        verify(repository, never()).markExpiredForTerminalRunUnknown(any(), any());
        verify(repository, never()).markExpiredExhaustedAttemptsUnknown(any(), any());
        verify(evidenceRecorder, never()).append(any(AppendEvidenceCommand.class));
    }

    /** 업그레이드 전 terminal 부모의 미시작 행도 선잠금한 정확한 부모 범위에서 정리한다. */
    @Test
    void strandedTerminalPendingDeliveriesAreCancelledWithinLockedParentScope() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        when(repository.cancelStrandedPendingForTerminalRun(RECOVERY_RUN_ID, now))
                .thenReturn(2);
        when(repository.markExpiredForTerminalRunUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of());
        when(repository.markExpiredExhaustedAttemptsUnknown(RECOVERY_RUN_ID, now))
                .thenReturn(List.of());

        assertThat(coordinator.recoverExpiredUncertainDeliveries(now)).isZero();

        InOrder order = inOrder(repository);
        order.verify(repository).lockNextRunForExpiredRecovery(now);
        order.verify(repository)
                .cancelStrandedPendingForTerminalRun(RECOVERY_RUN_ID, now);
        order.verify(repository)
                .markExpiredForTerminalRunUnknown(RECOVERY_RUN_ID, now);
        order.verify(repository)
                .markExpiredExhaustedAttemptsUnknown(RECOVERY_RUN_ID, now);
    }

    /** 테스트에 사용할 비민감 Callback 성공 Evidence를 만든다. */
    private AppendEvidenceCommand evidence() {
        return AppendEvidenceCommand.of(
                "run-1", EvidenceType.CALLBACK_DELIVERY_SUCCEEDED, EvidenceSource.DELIVERY);
    }

    /** 결정적 복구 순서 테스트에 사용할 UNKNOWN delivery를 만든다. */
    private CallbackDelivery unknownDelivery(String id, String runId, int occurrence) {
        CallbackDelivery delivery = mock(CallbackDelivery.class);
        when(delivery.id()).thenReturn(id);
        when(delivery.runId()).thenReturn(runId);
        when(delivery.occurrence()).thenReturn(occurrence);
        when(delivery.lastFailureCode())
                .thenReturn("DELIVERY_OUTCOME_UNKNOWN_AFTER_RUN_TERMINATION");
        return delivery;
    }
}
