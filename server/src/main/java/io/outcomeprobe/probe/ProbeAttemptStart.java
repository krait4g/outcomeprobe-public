package io.outcomeprobe.probe;

/**
 * Probe intent 시작 결과와 DB가 확정한 식별 정보를 묶는다.
 *
 * <p>책임: unique 충돌 때 호출자 후보가 아니라 최초 저장 행의 id·requestedAt을
 * 돌려줘 이후 complete·interrupt가 같은 durable attempt를 참조하게 한다.</p>
 *
 * @param intent DB 권위 행의 id와 요청 시각이 반영된 intent
 * @param state 신규 생성·진행 중·덮어쓰기 금지 기존 행 상태
 */
public record ProbeAttemptStart(ProbeAttemptIntent intent, ProbeAttemptStartState state) {

    /**
     * 반환 intent가 영속 식별자를 가지며 상태가 누락되지 않았는지 검증한다.
     *
     * @param intent DB 권위 intent
     * @param state 시작 상태
     */
    public ProbeAttemptStart {
        if (intent == null || intent.id() == null || intent.id().isBlank()) {
            throw new IllegalArgumentException("시작된 Probe attempt의 권위 id가 필요합니다.");
        }
        if (state == null) {
            throw new IllegalArgumentException("Probe attempt 시작 상태가 필요합니다.");
        }
    }
}
