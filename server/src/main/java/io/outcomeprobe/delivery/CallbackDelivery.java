package io.outcomeprobe.delivery;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Callback 사건의 전달 occurrence 하나를 나타낸다.
 *
 * <p>책임: 같은 사건을 몇 번째 전달하는지, 다음 시도 시각과 lease 소유자가
 * 누구인지 보존해 durable 재시도와 중복 전달을 가능하게 한다.</p>
 * <p>핵심 불변식: lease는 DB 조건과 version으로 보호되고 attempt count는 외부 HTTP 직전에
 * durable하게 증가한다. Worker crash 뒤 상한이 남으면 같은 occurrence를 재전달할 수 있고,
 * 마지막 attempt 결과가 불명확하면 UNKNOWN으로 종결한다.</p>
 *
 * @param id 행 식별자
 * @param runId 소속 Run 식별자
 * @param callbackEventId 소속 사건 식별자
 * @param occurrence 전달 순서(1부터)
 * @param status 현재 상태
 * @param scheduledAt 계획된 최초 전송 시각
 * @param nextAttemptAt 다음 시도 시각
 * @param attemptCount 시도 횟수
 * @param maximumAttempts 최대 시도 횟수
 * @param leaseOwner lease 소유 Worker
 * @param leaseUntil lease 만료 시각
 * @param lastFailureCode 마지막 실패 코드
 * @param requestSnapshot 전송에 필요한 요청 문맥 JSON
 * @param version optimistic version
 * @param completedAt 최종 상태 전환 시각
 */
public record CallbackDelivery(
        String id,
        String runId,
        String callbackEventId,
        int occurrence,
        DeliveryStatus status,
        Instant scheduledAt,
        Instant nextAttemptAt,
        int attemptCount,
        int maximumAttempts,
        String leaseOwner,
        Instant leaseUntil,
        String lastFailureCode,
        JsonNode requestSnapshot,
        long version,
        Instant completedAt) {
}
