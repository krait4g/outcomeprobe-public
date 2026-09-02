package io.outcomeprobe.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Callback 사건·전달 occurrence를 저장하고 lease를 관리하는 포트다.
 *
 * <p>책임: Delivery Worker가 due occurrence를 원자 lease하고 결과를 기록하는
 * 통로를 제공한다.</p>
 * <p>핵심 불변식: lease는 FOR UPDATE SKIP LOCKED로 획득돼 두 Worker가 같은
 * occurrence를 동시에 전송하지 않는다.</p>
 */
public interface CallbackDeliveryRepository {

    /**
     * Callback 사건을 저장한다.
     *
     * @param event 저장할 사건
     * @return 식별자가 채워진 사건
     */
    CallbackEvent saveEvent(CallbackEvent event);

    /**
     * 전달 occurrence를 저장한다.
     *
     * @param delivery 저장할 occurrence
     * @return 식별자가 채워진 occurrence
     */
    CallbackDelivery saveDelivery(CallbackDelivery delivery);

    /**
     * 사건을 조회한다.
     *
     * @param eventId 사건 식별자
     * @return 존재하면 사건
     */
    Optional<CallbackEvent> findEvent(String eventId);

    /**
     * Callback 상태 변경에 앞서 부모 Run 행을 잠근다.
     *
     * <p>모든 Callback 쓰기 경로가 scenario_run에서 callback_delivery 순서로 잠금을
     * 획득하게 해 terminal 전환과 Worker 결과 확정 사이의 교착을 막는다.</p>
     *
     * @param runId 부모 Run 식별자
     * @return Run이 존재해 잠금을 획득했으면 true
     */
    boolean lockRunForDeliveryMutation(String runId);

    /**
     * 복구 후보의 부모 Run 하나를 식별자 순으로 잠근다.
     *
     * <p>한 transaction이 여러 부모를 연달아 잠그며 다른 Worker와 교착하지 않도록 tick당
     * 부모 하나만 선택한다. 호출자는 반환된 식별자를 같은 transaction의 모든 복구 UPDATE에
     * 그대로 전달해야 한다.</p>
     *
     * @param now lease 만료 판정 시각
     * @return 잠근 복구 대상 부모 Run 식별자
     */
    Optional<String> lockNextRunForExpiredRecovery(Instant now);

    /**
     * 기한이 도래한 미전송 occurrence 하나를 원자 lease한다.
     *
     * <p>Run이 terminal(취소·오류)인 occurrence는 lease되지 않는다.</p>
     *
     * @param workerId lease 소유자
     * @param now 현재 시각
     * @param leaseDurationMs lease 유지 시간
     * @return lease 성공 시 occurrence
     */
    Optional<CallbackDelivery> leaseNextDue(String workerId, Instant now, long leaseDurationMs);

    /**
     * 외부 전송 직전 lease 권한을 CAS로 재확인하고 ATTEMPTING으로 전이한다.
     *
     * @param leased lease 시점 occurrence snapshot
     * @param now 권한·만료 확인 시각
     * @param leaseDurationMs 실제 전송 구간에 갱신할 lease 기간
     * @return 권한이 유지되면 version과 lease가 갱신된 snapshot
     */
    Optional<CallbackDelivery> beginAttempt(CallbackDelivery leased, Instant now,
                                            long leaseDurationMs);

    /**
     * Run terminal 전환과 함께 아직 시작하지 않은 전달을 취소한다.
     *
     * <p>SCHEDULED·RETRY_WAIT뿐 아니라 아직 HTTP 전인 LEASED도 취소해 beginAttempt CAS와
     * 같은 delivery 행에서 직렬화한다. 실제 HTTP를 시작한 ATTEMPTING은 건드리지 않는다.
     * 호출자는 이 변경을 Run terminal CAS와 같은 트랜잭션에 포함해야 한다.</p>
     *
     * @param runId terminal 전환할 Run 식별자
     * @param completedAt 취소 확정 시각
     * @return 취소한 occurrence 수
     */
    int cancelPendingForRun(String runId, Instant completedAt);

    /**
     * 이전 버전에서 terminal 부모 아래 남은 미시작 전달을 취소한다.
     *
     * <p>현재 terminal 전환 경로는 {@link #cancelPendingForRun(String, Instant)}으로 즉시
     * 정리하지만, 업그레이드 전에 남은 SCHEDULED·RETRY_WAIT는 별도 복구가 필요하다.
     * 이미 전송 가능성이 생긴 LEASED·ATTEMPTING은 이 메서드가 건드리지 않는다.</p>
     *
     * @param lockedRunId 같은 transaction에서 먼저 잠근 부모 Run 식별자
     * @param completedAt 취소 확정 시각
     * @return 취소한 occurrence 수
     */
    int cancelStrandedPendingForTerminalRun(String lockedRunId, Instant completedAt);

    /**
     * terminal Run에 남은 만료 lease를 결과 불명으로 확정한다.
     *
     * <p>lease는 전송 성공 여부를 증명하지 않으므로 CANCELLED나 DEAD로 단정하지 않는다.</p>
     *
     * @param lockedRunId 같은 transaction에서 먼저 잠근 부모 Run 식별자
     * @param now lease 만료 판정과 완료 시각
     * @return 결과 불명으로 전환한 occurrence snapshot
     */
    List<CallbackDelivery> markExpiredForTerminalRunUnknown(String lockedRunId, Instant now);

    /**
     * 재시도 한계를 모두 소비한 만료 in-flight 전달을 결과 불명으로 확정한다.
     *
     * <p>HTTP 직전 attempt count가 durable하게 증가하므로 마지막 시도 뒤 crash가 나도
     * 상한을 우회해 다시 보내지 않는다.</p>
     *
     * @param lockedRunId 같은 transaction에서 먼저 잠근 부모 Run 식별자
     * @param now lease 만료 판정과 완료 시각
     * @return 재시도할 수 없어 결과 불명으로 전환한 occurrence snapshot
     */
    List<CallbackDelivery> markExpiredExhaustedAttemptsUnknown(
            String lockedRunId, Instant now);

    /**
     * occurrence 상태를 optimistic version으로 갱신한다.
     *
     * <p>호출자는 부모 Run을 먼저 잠가야 한다. 대상 상태가 RETRY_WAIT이면 부모가
     * CANCELLED·ERROR·전역 deadline FAILED가 아닌 경우에만 갱신한다. HTTP 결과가 이미
     * 확정된 SUCCEEDED·DEAD 같은 최종 상태는 terminal 부모에서도 저장할 수 있다.</p>
     *
     * @param delivery 갱신할 occurrence
     * @return version 또는 부모 상태 fence를 모두 통과해 갱신했으면 true
     */
    boolean update(CallbackDelivery delivery);
}
