package io.outcomeprobe.foundation.support;

import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.foundation.SeedGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * foundation 모듈의 기본 포트 구현을 Spring bean으로 등록한다.
 *
 * <p>책임: 도메인·애플리케이션 코드가 인터페이스만 의존하고 구체 구현 선택은
 * 이 구성이 담당한다. 테스트에서는 이 bean들을 고정 clock·결정론 생성기로 재정의한다.</p>
 * <p>핵심 불변식: 이 구성은 업무 정책을 포함하지 않는다. 오직 기본 인프라 구현만 연결한다.</p>
 */
@Configuration
public class FoundationBeanConfiguration {

    /**
     * 기본 구성을 생성한다.
     */
    public FoundationBeanConfiguration() {
    }

    /**
     * UTC 시스템 시계 기반 기본 clock 제공자를 등록한다.
     *
     * @return 기본 {@link ClockProvider}
     */
    @Bean
    public ClockProvider clockProvider() {
        return new SystemClockProvider();
    }

    /**
     * 무작위 UUID 기반 기본 식별자 생성기를 등록한다.
     *
     * @return 기본 {@link IdGenerator}
     */
    @Bean
    public IdGenerator idGenerator() {
        return new RandomIdGenerator();
    }

    /**
     * SHA-256 해시 기반 결정론 seed 생성기를 등록한다.
     *
     * @return 기본 {@link SeedGenerator}
     */
    @Bean
    public SeedGenerator seedGenerator() {
        return new HashingSeedGenerator();
    }
}
