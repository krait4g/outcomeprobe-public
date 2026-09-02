package io.outcomeprobe.evidence;

/**
 * 실패 또는 지연의 기원 분류다.
 *
 * <p>책임: 운영자가 "도구가 망가진 것인지 Target이 실패한 것인지" 구분할 수 있게 한다.
 * Scenario가 의도한 장애, Target에서 관측된 실패, OutcomeProbe 내부 오류는
 * 보고와 재시도 정책이 서로 다르다.</p>
 * <p>핵심 불변식: OutcomeProbe 내부 재시도(DB serialization 실패 복구 등)는
 * Scenario fault로 보고되지 않는다.</p>
 */
public enum FaultOrigin {

    // Scenario가 의도적으로 계획한 장애다.
    SCENARIO_PLANNED,

    // Target 상호작용에서 관측된 실패다.
    TARGET_OBSERVED,

    // OutcomeProbe 자체의 내부 오류다.
    OUTCOMEPROBE_INTERNAL
}
