package io.outcomeprobe.scenario;

/**
 * OutcomeProbe가 관리하는 Artifact 종류다.
 *
 * <p>책임: import·저장·조회 API에서 Artifact 성격을 고정 vocabulary로 구분한다.</p>
 * <p>핵심 불변식: 공개 core API는 Contract·Scenario·Profile 세 종류만 허용한다.
 * {@code SCENARIO_PACK}은 기존 DB 행 역직렬화 호환을 위한 내부 식별자이며 신규 입력에는 허용하지 않는다.</p>
 */
public enum ArtifactKind {

    // Emulator가 제공할 API·Resource·Callback 계약이다.
    PROVIDER_CONTRACT,

    // 실패 계획·trigger·probe·assertion을 정의한 실행 단위다.
    SCENARIO,

    // Target base URI·허용 path·secret reference 정의다.
    TARGET_PROFILE,

    // 기존 DB 행 역직렬화 호환만을 위한 내부 식별자다. 공개 core API에서는 거부한다.
    SCENARIO_PACK
}
