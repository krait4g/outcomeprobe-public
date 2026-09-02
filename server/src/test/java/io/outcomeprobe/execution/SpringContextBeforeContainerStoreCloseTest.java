package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Spring TestContext가 컨테이너 역할의 JUnit Store resource보다 먼저 닫히는지 검증한다.
 *
 * <p>핵심 불변식은 DB-backed fixture 종료 시 scheduler와 datasource를 소유한 Spring context가
 * Testcontainers PostgreSQL보다 먼저 종료되는 것이다. 이 테스트는 실제 Docker를 사용하지 않고
 * JUnit Store의 자동 resource 종료 시점만 재현하므로 컨테이너 자체의 stop 동작은 검증하지 않는다.</p>
 */
@SpringJUnitConfig(SpringContextBeforeContainerStoreCloseTest.FixtureConfiguration.class)
@ExtendWith(SpringContextBeforeContainerStoreCloseExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SpringContextBeforeContainerStoreCloseTest {

    /** 테스트 본문 동안에는 Spring context가 정상적으로 활성 상태인지 확인한다. */
    @Test
    void keepsSpringContextActiveDuringTest(ConfigurableApplicationContext applicationContext) {
        assertThat(applicationContext.isActive()).isTrue();
    }

    /** 외부 인프라가 없는 최소 Spring TestContext를 만든다. */
    @Configuration(proxyBeanMethods = false)
    static class FixtureConfiguration {
    }
}
