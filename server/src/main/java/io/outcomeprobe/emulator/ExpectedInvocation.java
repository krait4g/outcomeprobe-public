package io.outcomeprobe.emulator;

import tools.jackson.databind.JsonNode;

/**
 * Scenario가 Target의 Provider 호출을 기다리며 등록한 expectation이다.
 *
 * <p>책임: Target이 호출할 operation, 요청 매칭 조건, fault 계획, 횟수 계약을
 * Emulator ingress가 조회해 요청을 수락·거부·장애 주입하는 기준으로 쓴다.</p>
 * <p>핵심 불변식: matchedCount는 maximumCount를 넘을 수 없으며, 횟수 증가는
 * version CAS로 원자적으로 일어난다. 충족되지 못한 expectation은 Run 최종
 * FAILED 판정의 근거다.</p>
 *
 * @param id 행 식별자
 * @param runId 소속 Run 식별자
 * @param stepId 등록한 provider.expect Step 식별자
 * @param operationKey Contract operation key
 * @param ordinal 등록 순서, 모호하지 않은 expectation 선택에 사용
 * @param matcher 요청 매칭 조건 JSON
 * @param behavior commit·responseFault·queryVisibility 계획 JSON
 * @param minimumCount 최소 요구 횟수
 * @param maximumCount 최대 허용 횟수
 * @param matchedCount 현재까지 매칭 횟수
 * @param version CAS용 버전
 */
public record ExpectedInvocation(
        String id,
        String runId,
        String stepId,
        String operationKey,
        int ordinal,
        JsonNode matcher,
        JsonNode behavior,
        int minimumCount,
        int maximumCount,
        int matchedCount,
        long version) {

    /**
     * 추가로 요청을 받아들일 수 있는 상태인지 반환한다.
     *
     * @return 최대 횟수 미만이면 true
     */
    public boolean hasCapacity() {
        return matchedCount < maximumCount;
    }

    /**
     * 최소 요구 횟수를 충족했는지 반환한다.
     *
     * @return 충족했으면 true
     */
    public boolean isSatisfied() {
        return matchedCount >= minimumCount;
    }
}
