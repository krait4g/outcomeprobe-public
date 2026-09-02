package io.outcomeprobe.probe;

import java.util.Optional;

/**
 * Probe attempt를 durable 저장소에 기록하는 출력 포트다.
 *
 * <p>책임: Probe 애플리케이션 로직이 SQL을 알지 않고 HTTP 전 intent를 시작하고,
 * HTTP 후 결과를 확정하거나 미확정 결과를 중단 상태로 전환하게 한다.</p>
 * <p>핵심 불변식: 같은 Run·Step·attempt 번호는 한 건만 존재한다. 시작 행에는 응답
 * 필드가 없고, 완료는 한 번만 허용되며, 응답 body 원문은 포트 계약에 포함되지 않는다.</p>
 */
public interface ProbeAttemptRepository {

    /**
     * Probe attempt 변경 전에 부모 Run을 잠그고 lease·상태 경계를 검증한다.
     *
     * <p>모든 Probe 쓰기 경로가 {@code scenario_run -> probe_attempt} 순서로 잠금을
     * 획득하게 해 Run terminal 전환과 결과 확정 사이의 교착을 막는다. 구현은 이미 열린
     * transaction 안에서 정확한 부모 행을 {@code FOR UPDATE}로 잠가야 한다.</p>
     *
     * @param fence Run lease와 변경 경계
     * @return 부모 Run이 현재 Worker에게 쓰기를 허용해 잠금을 획득했으면 true
     */
    boolean lockRunForProbeMutation(ProbeRunFence fence);

    /**
     * 응답 필드가 없는 intent를 시작하거나 같은 unique identity의 권위 행을 조회한다.
     *
     * @param intent 시작할 Probe attempt 의도
     * @return DB 권위 intent와 신규·기존 상태
     */
    ProbeAttemptStart begin(ProbeAttemptIntent intent);

    /**
     * 새 행을 만들지 않고 같은 Run·Step·attempt identity의 권위 행을 조회한다.
     *
     * <p>후보의 id와 requestedAt은 검색 조건이 아니다. deadline 뒤 복구가 과거 DB 행의
     * 식별자와 요청 시각을 그대로 사용하도록 identity 세 필드만 비교한다.</p>
     *
     * @param candidate 조회할 Run·Step·attempt identity 후보
     * @return 기존 행의 권위 intent와 진행 상태, 행이 없으면 empty
     */
    Optional<ProbeAttemptStart> find(ProbeAttemptIntent candidate);

    /**
     * 시작된 intent에 redaction과 재시도 판정이 끝난 결과를 한 번만 확정한다.
     *
     * <p>이미 완료·중단됐거나 식별자가 다른 행은 갱신하지 않고 예외로 실패한다.</p>
     *
     * @param attempt 확정할 Probe attempt
     */
    void complete(ProbeAttempt attempt);

    /**
     * 결과를 확정하지 못한 in-flight intent를 재시도 가능한 중단 상태로 전환한다.
     *
     * @param intent 중단할 DB 권위 intent
     * @param failureCode 중단 원인을 식별하는 안전한 코드
     * @return 조건부 갱신이 정확히 한 행에 적용됐으면 true
     */
    boolean interrupt(ProbeAttemptIntent intent, String failureCode);
}
