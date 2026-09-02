package io.outcomeprobe.execution;

import io.outcomeprobe.probe.AssertionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * execution 모듈의 순수 도메인 서비스와 스케줄링을 배선하는 구성이다.
 *
 * <p>책임: compiler·evaluator가 Spring 타입을 직접 의존하지 않게 유지하면서
 * Worker polling 스케줄을 활성화한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 구성은 실행 정책을 담지 않는다. 단순 배선만 수행한다.</p>
 */
@Configuration
@EnableScheduling
public class ExecutionModuleConfiguration {

    /**
     * 기본 구성을 생성한다.
     */
    public ExecutionModuleConfiguration() {
    }

    /**
     * Scenario 컴파일러를 등록한다.
     *
     * @return Scenario compiler
     */
    @Bean
    public ScenarioCompiler scenarioCompiler() {
        return new ScenarioCompiler();
    }

    /**
     * assertion 평가기를 등록한다.
     *
     * @return assertion evaluator
     */
    @Bean
    public AssertionEvaluator assertionEvaluator() {
        return new AssertionEvaluator();
    }
}
