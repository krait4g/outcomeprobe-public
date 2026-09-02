package io.outcomeprobe.evidence;

/**
 * Evidence를 생산한 주체 분류다.
 *
 * <p>책임: Timeline lane 구분과 fault 기원 추적을 위해 사건 발생 주체를 고정 vocabulary로
 * 나타낸다. UI는 이 값을 lane 그룹화 기준으로 사용한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 값은 장애의 원인 분류가 아니다. 장애가
 * Scenario 계획인지 내부 오류인지는 {@link FaultOrigin}으로 별도 기록한다.</p>
 */
public enum EvidenceSource {

    // Scenario 실행 엔진이 기록한 사건이다.
    ENGINE,

    // Target Application 상호작용에서 관측한 사건이다.
    TARGET,

    // Provider Emulator가 기록한 사건이다.
    EMULATOR,

    // Callback Delivery Worker가 기록한 사건이다.
    DELIVERY,

    // State Probe Worker가 기록한 사건이다.
    PROBE,

    // assertion 판정이 기록한 사건이다.
    ASSERTION
}
