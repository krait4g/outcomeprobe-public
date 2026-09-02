package org.outcomeprobe.referenceconsumer.payment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reference Consumer가 관리하는 결제 상태 snapshot이다.
 *
 * <p>책임: 최종 상태뿐 아니라 side effect 횟수(ledger·provider query·refund 호출)와
 * 처리된 이벤트 목록을 함께 보관해 OutcomeProbe Probe가 중복 side effect를 검출할
 * 수 있게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 객체는 데모용 in-memory 상태다. 영속화·
 * 분산 트랜잭션을 제공하지 않는다.</p>
 *
 * @param paymentId 결제 식별자
 * @param status 현재 상태
 * @param amount 승인 요청 금액
 * @param version 상태 변경 횟수
 * @param providerQueryCount Provider 상태 조회 호출 횟수
 * @param ledgerPostCount 원장 반영 횟수, 정상 수렴 시 1이어야 한다
 * @param refundRequestCount Provider 환불 API 호출 횟수, 재시도 폭주 검출에 사용한다
 * @param processedEventIds 처리 완료한 외부 이벤트 ID 집합
 */
public record Payment(
        String paymentId,
        PaymentStatus status,
        long amount,
        long version,
        int providerQueryCount,
        int ledgerPostCount,
        int refundRequestCount,
        Set<String> processedEventIds) {

    /**
     * 새 결제의 초기 상태를 만든다.
     *
     * @param paymentId 결제 식별자
     * @param amount 승인 요청 금액
     * @return CREATED 상태의 결제
     */
    public static Payment created(String paymentId, long amount) {
        return new Payment(paymentId, PaymentStatus.CREATED, amount, 0, 0, 0, 0, Set.of());
    }

    /**
     * 상태를 전이하고 version을 증가시킨 새 snapshot을 반환한다.
     *
     * @param newStatus 전이할 상태
     * @return version이 증가한 새 결제
     */
    public Payment transitionTo(PaymentStatus newStatus) {
        return new Payment(paymentId, newStatus, amount, version + 1,
                providerQueryCount, ledgerPostCount, refundRequestCount, processedEventIds);
    }

    /**
     * Provider 조회 횟수를 증가시킨 새 snapshot을 반환한다.
     *
     * @return 조회 횟수가 증가한 새 결제
     */
    public Payment withQueryCounted() {
        return new Payment(paymentId, status, amount, version,
                providerQueryCount + 1, ledgerPostCount, refundRequestCount, processedEventIds);
    }

    /**
     * 원장을 한 번 반영하고 성공 상태로 전이한 새 snapshot을 반환한다.
     *
     * <p>왜 원장 반영을 상태 전이와 묶는가: 중복 side effect 없이 정확히 1회
     * 반영돼야 Probe의 ledgerPostCount assertion이 의미를 가진다.</p>
     *
     * @return 원장 1회 반영·SUCCEEDED 상태의 새 결제
     */
    public Payment finalizeSucceeded() {
        return new Payment(paymentId, PaymentStatus.SUCCEEDED, amount, version + 1,
                providerQueryCount, ledgerPostCount + 1, refundRequestCount, processedEventIds);
    }

    /**
     * 환불 요청을 시작한다. Provider 호출 횟수를 기록해 재시도 폭주를 검출할 수 있게 한다.
     *
     * @return REFUNDING 상태이면서 refundRequestCount가 증가한 새 결제
     */
    public Payment startRefund() {
        return new Payment(paymentId, PaymentStatus.REFUNDING, amount, version + 1,
                providerQueryCount, ledgerPostCount, refundRequestCount + 1, processedEventIds);
    }

    /**
     * 환불 결과를 즉시 실패로 확정한다.
     *
     * <p>왜 위험한가: Provider는 이미 환불을 commit했을 수 있다. 조회 없이 실패로
     * 확정하면 내부 상태가 Provider truth와 영구히 어긋난다. 취약 정책의 의도적 행동이다.</p>
     *
     * @return FAILED 상태의 새 결제
     */
    public Payment markRefundFailed() {
        return new Payment(paymentId, PaymentStatus.FAILED, amount, version + 1,
                providerQueryCount, ledgerPostCount, refundRequestCount, processedEventIds);
    }

    /**
     * 환불 완료로 수렴시킨다.
     *
     * @return REFUNDED 상태의 새 결제
     */
    public Payment finalizeRefunded() {
        return new Payment(paymentId, PaymentStatus.REFUNDED, amount, version + 1,
                providerQueryCount, ledgerPostCount, refundRequestCount, processedEventIds);
    }

    /**
     * Probe 노출용 목록을 포함한 snapshot을 반환한다.
     *
     * @param events 처리된 이벤트 ID 목록
     * @return 이벤트 목록이 채워진 새 결제
     */
    public Payment withProcessedEvents(List<String> events) {
        return new Payment(paymentId, status, amount, version,
                providerQueryCount, ledgerPostCount, refundRequestCount, Set.copyOf(events));
    }

    /**
     * 중복 제거 없이 성공 이벤트를 반영한다.
     *
     * <p>왜 위험한가: 같은 eventId가 여러 번 도착할 때마다 원장이 중복 반영돼
     * OutcomeProbe의 side effect count assertion이 실패한다. 취약 정책의 의도적 행동이다.</p>
     *
     * @return 원장이 다시 반영된 새 결제
     */
    public Payment withSuccessEventNoDedup() {
        return new Payment(paymentId, PaymentStatus.SUCCEEDED, amount, version + 1,
                providerQueryCount, ledgerPostCount + 1, refundRequestCount, processedEventIds);
    }

    /**
     * eventId 중복 제거와 함께 성공 이벤트를 반영한다.
     *
     * <p>이미 처리한 eventId면 아무것도 바꾸지 않는다. 이미 SUCCEEDED로 수렴한
     * 결제(예: Provider 응답으로 확정)는 이벤트만 기록하고 원장을 다시 반영하지
     * 않는다. 최초 성공 확정만 원장을 정확히 1회 반영한다.</p>
     *
     * @param eventId 성공 이벤트 식별자
     * @return 변경이 있었으면 새 결제, 중복이면 empty
     */
    public Optional<Payment> withSuccessEventIfNew(String eventId) {
        if (processedEventIds.contains(eventId)) {
            return Optional.empty();
        }
        Set<String> events = new LinkedHashSet<>(processedEventIds);
        events.add(eventId);
        // 이미 SUCCEEDED면 원장 중복 반영 없이 이벤트만 기록한다.
        if (status == PaymentStatus.SUCCEEDED) {
            return Optional.of(new Payment(paymentId, status, amount, version + 1,
                    providerQueryCount, ledgerPostCount, refundRequestCount, Set.copyOf(events)));
        }
        return Optional.of(new Payment(paymentId, PaymentStatus.SUCCEEDED, amount, version + 1,
                providerQueryCount, ledgerPostCount + 1, refundRequestCount, Set.copyOf(events)));
    }
}
