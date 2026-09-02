package io.outcomeprobe.probe;

/**
 * {@link ProbeAttemptRepository#begin(ProbeAttemptIntent)} 결과의 권위 상태다.
 *
 * <p>책임: 신규 intent 생성과 unique 충돌로 발견한 기존 intent를 구분해,
 * 호출자가 HTTP 재실행과 Evidence 중복 기록을 안전하게 결정하게 한다.</p>
 */
public enum ProbeAttemptStartState {

    // 이번 begin 호출이 intent 행을 새로 만들었다.
    CREATED,

    // 같은 Run·Step·attempt의 기존 행이 아직 결과 확정 전이다.
    EXISTING_IN_FLIGHT,

    // 결과 컬럼이 하나라도 채워져 정상 확정 또는 partial/legacy 보호 대상으로 분류됐다.
    EXISTING_FINALIZED
}
