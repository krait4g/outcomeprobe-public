package io.outcomeprobe.probe;

import java.time.Instant;
import java.util.List;

/**
 * State Probe의 독립적인 HTTP 관측 attempt를 보존한다.
 *
 * <p>책임: 요청·응답 시각, HTTP 상태, 원본 바이트 해시, redaction된 assertion 결과와
 * 다음 결정을 durable record로 전달한다.</p>
 * <p>핵심 불변식: 응답 body 원문은 이 모델에 존재하지 않는다. 따라서 persistence와
 * Evidence 계층이 이 객체를 통해 원문을 저장할 수 없다.</p>
 *
 * @param id 저장 식별자, 신규 저장 전에는 null
 * @param runId 소속 Run 식별자
 * @param stepId 소속 Step 식별자
 * @param attemptNumber Step 안의 1부터 시작하는 시도 번호
 * @param requestedAt 요청 시작 관측 시각
 * @param respondedAt 응답 또는 실패 관측 시각
 * @param statusCode HTTP 상태 코드, 연결 전 실패면 0
 * @param responseHash 완전히 받은 원본 응답 바이트 SHA-256, 없으면 null
 * @param assertionOutcomes redaction 적용이 끝난 assertion 결과
 * @param decision 이 attempt의 실행 결정
 * @param failureCode 관측 실패 원인 코드, 없으면 null
 */
public record ProbeAttempt(
        String id,
        String runId,
        String stepId,
        int attemptNumber,
        Instant requestedAt,
        Instant respondedAt,
        int statusCode,
        String responseHash,
        List<AssertionEvaluator.AssertionOutcome> assertionOutcomes,
        ProbeDecision decision,
        String failureCode) {

    /**
     * 시도 번호와 시각 순서를 검증하고 assertion 목록을 방어적으로 복사한다.
     *
     * @param id 저장 식별자
     * @param runId 소속 Run 식별자
     * @param stepId 소속 Step 식별자
     * @param attemptNumber 시도 번호
     * @param requestedAt 요청 시각
     * @param respondedAt 응답 시각
     * @param statusCode HTTP 상태 코드
     * @param responseHash 응답 해시
     * @param assertionOutcomes assertion 결과
     * @param decision 실행 결정
     * @param failureCode 실패 코드
     */
    public ProbeAttempt {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Probe attempt 번호는 1 이상이어야 합니다.");
        }
        if (requestedAt == null || respondedAt == null || respondedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("Probe 요청·응답 시각 순서가 올바르지 않습니다.");
        }
        assertionOutcomes = assertionOutcomes == null ? List.of() : List.copyOf(assertionOutcomes);
    }

    /**
     * persistence가 발급한 식별자를 반영한 새 attempt를 반환한다.
     *
     * @param newId 저장소가 발급한 식별자
     * @return 식별자가 채워진 attempt
     */
    public ProbeAttempt withId(String newId) {
        return new ProbeAttempt(newId, runId, stepId, attemptNumber, requestedAt, respondedAt,
                statusCode, responseHash, assertionOutcomes, decision, failureCode);
    }
}
