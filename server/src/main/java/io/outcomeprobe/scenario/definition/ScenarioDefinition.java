package io.outcomeprobe.scenario.definition;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 실패 계획과 최종 상태 assertion을 정의한 Scenario 도메인 모델이다.
 *
 * <p>책임: YAML spec을 타입 안전 record로 고정해 compiler와 validator가
 * 문자열 탐색 없이 업무를 처리하게 한다.</p>
 * <p>핵심 불변식: Step의 동적 payload(request body, assertion 값)만 JSON 트리로
 * 유지하고, 구조 필드는 전부 타입으로 표현한다. Scenario에는 absolute URL과
 * secret literal이 존재할 수 없다.</p>
 *
 * @param contractRef 이 Scenario가 사용하는 Provider Contract 참조
 * @param targetProfileRef 기본 Target Profile 이름(Run 생성 시 override 가능)
 * @param seed 기본 결정론 seed, Run 생성 시 override 가능
 * @param timeout Run 전체 제한 시간
 * @param variables Scenario 변수 기본값
 * @param steps 실행 순서가 고정된 Step 목록
 */
public record ScenarioDefinition(
        ContractRef contractRef,
        String targetProfileRef,
        Long seed,
        Duration timeout,
        Map<String, Object> variables,
        List<StepSpec> steps) {

    /**
     * Provider Contract 참조다.
     *
     * @param name Contract 이름
     * @param version Contract SemVer 버전
     */
    public record ContractRef(String name, String version) {
    }

    /**
     * Scenario Step 하나의 원본 정의다.
     *
     * <p>type별 상세 필드는 {@code detail} JSON에 남기고, compiler가 타입 안전
     * 명령으로 해석한다. 이 분리는 DSL 확장을 core switch 문으로 오염시키지 않기
     * 위한 경계다.</p>
     *
     * @param id Step 고유 식별자(Scenario 내 유일)
     * @param type Step 유형 문자열
     * @param detail Step 유형별 상세 필드를 포함한 step 전체 JSON
     */
    public record StepSpec(String id, String type, JsonNode detail) {
    }
}
