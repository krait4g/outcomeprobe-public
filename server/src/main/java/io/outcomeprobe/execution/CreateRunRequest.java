package io.outcomeprobe.execution;

import java.util.Map;

/**
 * Run 생성 요청을 표현한다.
 *
 * <p>책임: Control API와 CLI가 동일한 Run 생성 계약을 사용하게 한다.</p>
 *
 * @param scenarioRef 실행할 Scenario 이름·버전
 * @param targetProfile 대상 Target Profile 이름, 없으면 Scenario 기본값
 * @param seed 결정론 seed override, 없으면 Scenario 기본값
 * @param variables 변수 override
 * @param waitForAlias alias 점유 시 대기 여부, M1은 미지원 예약 필드
 */
public record CreateRunRequest(
        ScenarioRef scenarioRef,
        String targetProfile,
        Long seed,
        Map<String, Object> variables,
        Boolean waitForAlias) {

    /**
     * Scenario 참조다.
     *
     * @param name Scenario 이름
     * @param version Scenario 버전
     */
    public record ScenarioRef(String name, String version) {
    }
}
