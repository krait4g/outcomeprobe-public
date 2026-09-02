package io.outcomeprobe.emulator;

import java.time.Instant;
import java.util.Map;

/**
 * Run 내부에서 Emulator가 관리하는 Provider truth 자원이다.
 *
 * <p>책임: 외부 제공자 내부에서 실제로 commit된 상태(canonicalState)와 조회 API에
 * 노출되는 상태(visibleState)를 분리해 eventual consistency 상황을 재현한다.</p>
 * <p>핵심 불변식: 상태 변경은 version CAS로만 일어난다. canonicalState는 Provider
 * 내부 진실이며 Target이 믿는 상태와 다를 수 있다. 이 차이는 검증 대상이지 버그가 아니다.</p>
 *
 * @param id 행 식별자
 * @param runId 소속 Run 식별자
 * @param resourceType Contract에 정의된 resource 유형
 * @param resourceId 자원 식별자
 * @param canonicalState Provider 내부 실제 상태
 * @param visibleState 조회 API에 현재 노출되는 상태
 * @param version CAS용 버전, 전이마다 정확히 1 증가
 * @param remainingStaleQueryAttempts 오래된 조회 응답을 반환할 남은 횟수
 * @param visibleAfter 이 시각 이전까지 조회가 오래된 상태를 반환하는 시간 기준 stale, 없으면 null
 * @param attributes 상태 부가 속성
 * @param updatedAt 마지막 변경 시각
 */
public record ProviderResource(
        String id,
        String runId,
        String resourceType,
        String resourceId,
        String canonicalState,
        String visibleState,
        long version,
        int remainingStaleQueryAttempts,
        Instant visibleAfter,
        Map<String, Object> attributes,
        Instant updatedAt) {

    /**
     * 이 자원이 아직 오래된 조회 응답을 반환해야 하는지 여부다.
     *
     * <p>횟수 기준 stale만 본다. 시간 기준(visibleAfter)은 consumeQuery가 시각으로 판단한다.</p>
     *
     * @return stale 조회가 남았으면 true
     */
    public boolean hasStaleView() {
        return remainingStaleQueryAttempts > 0;
    }

    /**
     * 지정 시각에 시간 기준 stale이 아직 유효한지 여부다.
     *
     * @param now 판단 기준 시각
     * @return visibleAfter 이전이면 true
     */
    public boolean isTimeStaleAt(Instant now) {
        return visibleAfter != null && now.isBefore(visibleAfter);
    }
}
