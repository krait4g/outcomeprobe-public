package io.outcomeprobe.probe;

import java.time.Instant;

/**
 * HTTP 요청 전에 영속화하는 Probe attempt 의도다.
 *
 * <p>책임: 응답과 판정이 아직 없는 요청 식별 정보만 전달해, 프로세스 중단 뒤에도
 * 어떤 Run·Step의 몇 번째 관측이 시작됐는지 복구할 수 있게 한다.</p>
 * <p>핵심 불변식: 입력 id는 신규 후보 발급 전 null일 수 있지만, 나머지 식별자와
 * 요청 시각은 항상 유효하다. 응답 관련 값은 이 모델에 존재하지 않는다.</p>
 *
 * @param id attempt 식별자, JDBC가 신규 후보를 발급하기 전에는 null
 * @param runId 소속 Run 식별자
 * @param stepId 소속 Step 식별자
 * @param attemptNumber Step 안의 1부터 시작하는 시도 번호
 * @param requestedAt HTTP 전송 의도를 확정한 관측 시각
 */
public record ProbeAttemptIntent(
        String id,
        String runId,
        String stepId,
        int attemptNumber,
        Instant requestedAt) {

    /**
     * 필수 식별자·시도 번호·요청 시각을 검증한다.
     *
     * @param id attempt 식별자, 신규 후보 발급 전에는 null 가능
     * @param runId 소속 Run 식별자
     * @param stepId 소속 Step 식별자
     * @param attemptNumber 시도 번호
     * @param requestedAt 전송 의도 확정 시각
     */
    public ProbeAttemptIntent {
        if (id != null && id.isBlank()) {
            throw new IllegalArgumentException("Probe attempt id는 비어 있을 수 없습니다.");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Probe attempt runId가 필요합니다.");
        }
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("Probe attempt stepId가 필요합니다.");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Probe attempt 번호는 1 이상이어야 합니다.");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("Probe attempt 요청 시각이 필요합니다.");
        }
    }
}
