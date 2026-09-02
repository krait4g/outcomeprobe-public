package io.outcomeprobe.execution;

/**
 * Scenario DSL이 지원하는 Step 유형 사전이다.
 *
 * <p>책임: Scenario 확장이 core switch 문 증가로 이어지지 않도록 고정된
 * Step 유형 vocabulary를 정의한다. v1.0은 이 목록 밖의 Step 유형을 추가하지 않는다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 모든 유형이 M1에서 구현되는 것은 아니다.
 * 미구현 유형은 compiler가 명시적 오류로 거부한다.</p>
 */
public enum StepType {

    // Target 초기화·업무 trigger 호출이다.
    TARGET_HTTP,

    // Provider truth fixture 생성이다.
    PROVIDER_SEED,

    // Target이 호출할 Provider operation과 fault 계획 등록이다.
    PROVIDER_EXPECT,

    // Callback 전달 계획 생성이다.
    CALLBACK_SCHEDULE,

    // 특정 상호작용 Evidence가 발생할 때까지 대기다.
    EVIDENCE_AWAIT,

    // Target State가 제한 시간 안에 assertion을 만족하는지 반복 조회다.
    PROBE_EVENTUALLY,

    // Timeline의 횟수·순서·속성 판정이다.
    EVIDENCE_ASSERT,

    // Provider truth와 호출 횟수 판정이다.
    PROVIDER_ASSERT,

    // 의도적인 논리 시간 지연이다.
    RUN_WAIT;

    /**
     * DSL 문자열을 StepType으로 해석한다.
     *
     * @param dslValue {@code target.http} 형식 문자열
     * @return 해석된 StepType
     * @throws IllegalArgumentException 알 수 없는 유형이면 발생한다
     */
    public static StepType fromDsl(String dslValue) {
        return switch (dslValue) {
            case "target.http" -> TARGET_HTTP;
            case "provider.seed" -> PROVIDER_SEED;
            case "provider.expect" -> PROVIDER_EXPECT;
            case "callback.schedule" -> CALLBACK_SCHEDULE;
            case "evidence.await" -> EVIDENCE_AWAIT;
            case "probe.eventually" -> PROBE_EVENTUALLY;
            case "evidence.assert" -> EVIDENCE_ASSERT;
            case "provider.assert" -> PROVIDER_ASSERT;
            case "run.wait" -> RUN_WAIT;
            default -> throw new IllegalArgumentException("알 수 없는 step type입니다: " + dslValue);
        };
    }
}
