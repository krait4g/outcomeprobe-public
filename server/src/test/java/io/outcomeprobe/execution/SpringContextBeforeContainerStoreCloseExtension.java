package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * DB-backed fixture의 Spring context가 JUnit class Store보다 먼저 닫혔는지 검증한다.
 *
 * <p>책임은 테스트 중 활성 context를 기억하고 Testcontainers가 사용하는 Store resource 종료
 * 시점에 이미 비활성인지 fail-closed로 확인하는 것이다. 실제 컨테이너를 시작하거나 멈추지는
 * 않으며, fixture가 {@code @DirtiesContext(AFTER_CLASS)}를 함께 선언해야 한다.</p>
 */
@SuppressWarnings("deprecation")
public final class SpringContextBeforeContainerStoreCloseExtension
        implements BeforeAllCallback, AfterEachCallback,
        ExtensionContext.Store.CloseableResource {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SpringContextBeforeContainerStoreCloseExtension.class);

    private ConfigurableApplicationContext applicationContext;

    /** JUnit class Store에 실제 fixture 종료 순서를 감시할 resource를 등록한다. */
    @Override
    public void beforeAll(ExtensionContext context) {
        context.getStore(NAMESPACE).put(
                SpringContextBeforeContainerStoreCloseExtension.class, this);
    }

    /** Store 종료 전에 동일 Spring context가 활성 상태였음을 기록한다. */
    @Override
    public void afterEach(ExtensionContext context) {
        this.applicationContext = (ConfigurableApplicationContext)
                SpringExtension.getApplicationContext(context);
        assertThat(applicationContext.isActive()).isTrue();
    }

    /** 외부 resource 종료 시점에는 Spring context가 먼저 닫혔는지 검증한다. */
    @Override
    public void close() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.isActive()).isFalse();
    }
}
