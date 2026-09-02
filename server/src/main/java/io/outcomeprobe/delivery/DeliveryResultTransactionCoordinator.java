package io.outcomeprobe.delivery;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.OptimisticStateTransitionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Callback HTTP 호출 뒤 delivery 결과와 Evidence를 원자적으로 확정한다.
 *
 * <p>책임: 외부 전송이 끝난 뒤 SUCCEEDED·RETRY_WAIT·DEAD 상태와 그 근거를 같은
 * 트랜잭션에 저장한다. 핵심 불변식은 delivery CAS 실패를 성공처럼 무시하지 않고
 * 예외로 전파해 Evidence까지 rollback시키는 것이다.</p>
 * <p>WebhookSenderPort를 의존하지 않으므로 원격 HTTP는 이 트랜잭션 밖에서만
 * 실행된다.</p>
 */
@Service
public class DeliveryResultTransactionCoordinator {

    private final CallbackDeliveryRepository deliveryRepository;
    private final EvidenceRecorder evidenceRecorder;

    /**
     * 결과 저장에 필요한 영속 포트만 주입한다.
     *
     * @param deliveryRepository delivery 상태 저장 포트
     * @param evidenceRecorder append-only Evidence 기록 포트
     */
    public DeliveryResultTransactionCoordinator(CallbackDeliveryRepository deliveryRepository,
                                                EvidenceRecorder evidenceRecorder) {
        this.deliveryRepository = deliveryRepository;
        this.evidenceRecorder = evidenceRecorder;
    }

    /**
     * delivery CAS와 관련 Evidence 전체를 한 트랜잭션에서 확정한다.
     *
     * <p>CAS가 0건이면 lease를 잃은 Worker의 결과이므로 예외를 던져 이후 Evidence를
     * 기록하지 않고, 앞선 변경도 rollback되게 한다.</p>
     * <p>HTTP 처리 중 부모 Run이 취소·오류·전역 deadline 실패로 끝났다면 RETRY_WAIT
     * fence도 0건을 반환한다. 이 경우 ATTEMPTING과 lease를 그대로 두어 만료 복구가
     * 전송 결과를 UNKNOWN으로 확정하게 한다. SUCCEEDED·DEAD는 terminal 부모에서도
     * 외부에서 관측된 실제 결과이므로 저장한다.</p>
     *
     * @param delivery 외부 호출 결과가 반영된 delivery snapshot
     * @param evidenceCommands 같은 결과를 설명하는 Evidence 명령 목록
     * @throws OptimisticStateTransitionException delivery version 또는 부모 상태 fence가
     *         전이를 거부할 때 발생한다
     */
    @Transactional
    public void commit(CallbackDelivery delivery,
                       List<AppendEvidenceCommand> evidenceCommands) {
        requireRunLock(delivery.runId());
        // lease owner와 version을 잃은 Worker가 Timeline에 거짓 결과를 남기지 못하게 한다.
        if (!deliveryRepository.update(delivery)) {
            String transition = delivery.status() == DeliveryStatus.RETRY_WAIT
                    ? "Callback RETRY_WAIT 전이가 부모 상태 또는 CAS fence에서 거부되었습니다: "
                    : "Callback delivery CAS에 실패했습니다: ";
            throw new OptimisticStateTransitionException(
                    transition + delivery.id());
        }
        List.copyOf(evidenceCommands).forEach(evidenceRecorder::append);
    }

    /**
     * 외부 HTTP 직전 lease 권한과 ATTEMPTED Evidence를 원자 확정한다.
     *
     * <p>CAS가 실패하면 Evidence를 남기지 않고 empty를 반환한다. 호출자는 empty일 때
     * WebhookSenderPort를 호출하면 안 된다.</p>
     *
     * @param leased lease 시점 occurrence snapshot
     * @param now 권한 확인 시각
     * @param leaseDurationMs 실제 전송 구간에 갱신할 lease 기간
     * @param attemptedEvidence 외부 전송 의도 Evidence
     * @return 권한이 유지되면 ATTEMPTING snapshot
     */
    @Transactional
    public Optional<CallbackDelivery> beginAttempt(
            CallbackDelivery leased, Instant now, long leaseDurationMs,
            AppendEvidenceCommand attemptedEvidence) {
        if (!deliveryRepository.lockRunForDeliveryMutation(leased.runId())) {
            return Optional.empty();
        }
        Optional<CallbackDelivery> attempting =
                deliveryRepository.beginAttempt(leased, now, leaseDurationMs);
        attempting.ifPresent(ignored -> evidenceRecorder.append(attemptedEvidence));
        return attempting;
    }

    /**
     * terminal Run의 구버전 pending과 만료·상한 소진 delivery를 한 부모 범위에서 정리한다.
     *
     * <p>HTTP가 실제로 전송됐는지 알 수 없으므로 CANCELLED나 실패로 단정하지 않는다.
     * Delivery 갱신과 Evidence 중 하나라도 실패하면 전체를 rollback한다.</p>
     *
     * @param now lease 만료 판정과 관측 시각
     * @return 결과 불명으로 닫고 Evidence를 기록한 occurrence 수
     */
    @Transactional
    public int recoverExpiredUncertainDeliveries(Instant now) {
        Optional<String> lockedRunId = deliveryRepository.lockNextRunForExpiredRecovery(now);
        if (lockedRunId.isEmpty()) {
            return 0;
        }
        String runId = lockedRunId.get();
        deliveryRepository.cancelStrandedPendingForTerminalRun(runId, now);
        List<CallbackDelivery> recovered = new ArrayList<>(
                deliveryRepository.markExpiredForTerminalRunUnknown(runId, now));
        recovered.addAll(deliveryRepository.markExpiredExhaustedAttemptsUnknown(runId, now));
        // UPDATE RETURNING의 비결정적 순서가 Evidence sequence를 흔들지 않게 정렬한다.
        recovered.sort(Comparator.comparing(CallbackDelivery::runId)
                .thenComparingInt(CallbackDelivery::occurrence)
                .thenComparing(CallbackDelivery::id));
        recovered.forEach(delivery -> evidenceRecorder.append(AppendEvidenceCommand
                .of(delivery.runId(), EvidenceType.CALLBACK_DELIVERY_OUTCOME_UNKNOWN,
                        EvidenceSource.DELIVERY)
                .withAttribute("deliveryId", delivery.id())
                .withAttribute("occurrence", String.valueOf(delivery.occurrence()))
                .withAttribute("transmissionState", "UNKNOWN")
                .withAttribute("reason", delivery.lastFailureCode())
                .withObservedAt(now)));
        return recovered.size();
    }

    /**
     * 부모 Run 잠금 실패를 delivery CAS 실패와 같은 트랜잭션 오류로 바꾼다.
     *
     * @param runId 부모 Run 식별자
     * @throws OptimisticStateTransitionException 부모 Run이 사라져 잠글 수 없을 때 발생한다
     */
    private void requireRunLock(String runId) {
        if (!deliveryRepository.lockRunForDeliveryMutation(runId)) {
            throw new OptimisticStateTransitionException(
                    "Callback 부모 Run 잠금에 실패했습니다: " + runId);
        }
    }
}
