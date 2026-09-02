package io.outcomeprobe.emulator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * emulator 모듈의 순수 도메인 도구를 bean으로 등록하는 구성이다.
 *
 * <p>책임: template 렌더러와 matcher 평가기가 Spring 타입을 직접 의존하지
 * 않으면서도 서비스에 주입되게 연결한다.</p>
 */
@Configuration
public class EmulatorModuleConfiguration {

    /**
     * 기본 구성을 생성한다.
     */
    public EmulatorModuleConfiguration() {
    }

    /**
     * 응답·Callback template 렌더러를 등록한다.
     *
     * @return template 렌더러
     */
    @Bean
    public TemplateRenderer templateRenderer() {
        return new TemplateRenderer();
    }

    /**
     * 요청 matcher 평가기를 등록한다.
     *
     * @return matcher 평가기
     */
    @Bean
    public RequestMatcherEvaluator requestMatcherEvaluator() {
        return new RequestMatcherEvaluator();
    }
}
