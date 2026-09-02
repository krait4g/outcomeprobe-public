package io.outcomeprobe.execution.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.outcomeprobe.execution.TargetHttpPort;
import io.outcomeprobe.infrastructure.net.NetworkSafety;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Target HTTP adapter의 Spring 생성자 선택 계약을 검증한다.
 *
 * <p>책임: 테스트 제어용 보조 생성자가 존재해도 운영 의존성을 받는 생성자로 Bean이
 * 만들어지는지 확인한다. 핵심 불변식: Spring context refresh는 기본 생성자를 요구하지
 * 않고 {@link TargetHttpPort} 구현을 정확히 구성해야 한다.</p>
 */
class HttpClientTargetAdapterSpringContextTest {

    /**
     * Spring이 운영용 생성자를 선택해 Target HTTP Port Bean을 구성한다.
     *
     * <p>이 회귀는 package-private 테스트 생성자가 추가될 때 Component가 기본 생성자를
     * 찾다가 애플리케이션 시작을 막는 결함을 검출한다.</p>
     */
    @Test
    void springSelectsProductionConstructorWhenTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(NetworkSafety.class, () -> mock(NetworkSafety.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(HttpClientTargetAdapter.class);

            context.refresh();

            assertThat(context.getBean(TargetHttpPort.class))
                    .isInstanceOf(HttpClientTargetAdapter.class);
        }
    }
}
