package io.outcomeprobe.probe;

/**
 * Probe attempt 한 건이 다음 실행 흐름에 미치는 결정을 나타낸다.
 *
 * <p>책임: 명세의 여섯 가지 Probe 결과 분류를 그대로 표현해 DB와 Evidence가
 * 같은 기계 판독 값을 공유하게 한다.</p>
 * <p>핵심 불변식: 일시 관측 상태와 연결 실패만 재시도할 수 있으며, 보안 거부와
 * 반복 불가능 응답은 즉시 중단된다.</p>
 */
public enum ProbeDecision {

    // assertion 또는 stability 조건이 아직 수렴하지 않은 재시도 상태다.
    PENDING,

    // 모든 assertion과 stability 조건을 충족한 최종 성공이다.
    CONVERGED,

    // deadline까지 수렴하지 못한 Scenario 실패다.
    DEADLINE_EXCEEDED,

    // JSON·크기·HTTP 계약상 반복할 수 없는 영구 응답 오류다.
    INVALID_RESPONSE,

    // 연결 실패나 timeout으로 Target에 도달하지 못한 일시 상태다.
    TARGET_UNREACHABLE,

    // Profile allowlist나 요청 안전 규칙이 요청을 거부한 영구 오류다.
    SECURITY_REJECTED;

    /**
     * deadline 전에 같은 Step을 다시 관측해야 하는 분류인지 반환한다.
     *
     * @return 일시 상태 또는 연결 실패면 true
     */
    public boolean shouldRetry() {
        return this == PENDING || this == TARGET_UNREACHABLE;
    }

    /**
     * 모든 수렴 조건을 충족한 최종 성공인지 반환한다.
     *
     * @return 최종 성공이면 true
     */
    public boolean isConverged() {
        return this == CONVERGED;
    }

    /**
     * Scenario 기대가 deadline까지 충족되지 않은 실패인지 반환한다.
     *
     * @return deadline 실패면 true
     */
    public boolean isDeadlineExceeded() {
        return this == DEADLINE_EXCEEDED;
    }

    /**
     * 재시도할 수 없는 Profile·응답 오류인지 반환한다.
     *
     * @return 즉시 ERROR로 종료해야 하면 true
     */
    public boolean isPermanentError() {
        return this == INVALID_RESPONSE || this == SECURITY_REJECTED;
    }
}
