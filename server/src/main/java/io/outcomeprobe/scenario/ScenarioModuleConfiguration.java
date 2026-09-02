package io.outcomeprobe.scenario;

import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * scenario 모듈의 순수 도메인 서비스를 bean으로 등록하는 구성이다.
 *
 * <p>책임: parser·mapper가 Spring 타입을 직접 의존하지 않으면서도 컨텍스트에서
 * 주입 가능하게 연결한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 구성은 업무 정책을 담지 않는다. 단순 배선만 수행한다.</p>
 */
@Configuration
public class ScenarioModuleConfiguration {

    /**
     * 기본 구성을 생성한다.
     */
    public ScenarioModuleConfiguration() {
    }

    /**
     * YAML envelope 해석기를 등록한다.
     *
     * @return Artifact 파서
     */
    @Bean
    public ArtifactParser artifactParser() {
        return new ArtifactParser(new io.outcomeprobe.scenario.SchemaValidationService());
    }

    /**
     * Contract·Scenario 구조 변환·검증기를 등록한다.
     *
     * @param objectMapper JSON 변환에 사용할 공유 mapper
     * @return Scenario mapper
     */
    @Bean
    public ScenarioMapper scenarioMapper(ObjectMapper objectMapper) {
        return new ScenarioMapper(objectMapper);
    }
}
