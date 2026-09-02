package io.outcomeprobe;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith 기반 모듈 경계 검증 테스트다.
 *
 * <p>책임: {@code io.outcomeprobe} 루트 아래 모듈들이 허용되지 않은 내부 패키지
 * 참조를 만들지 못하게 막는다. 이 테스트가 실패하면 모듈 결합이 설계 경계를
 * 벗어났다는 뜻이다.</p>
 * <p>검출 가능한 결함: 다른 모듈의 internal·adapter 패키지 직접 참조, 모듈 간
 * 순환 의존, 비공개 타입 노출.</p>
 */
class ModularityTests {

    /**
     * 루트 애플리케이션 기준으로 모든 모듈 경계를 검증한다.
     *
     * <p>Spring 컨텍스트를 띄우지 않는 정적 분석이므로 Docker나 DB 없이 실행된다.</p>
     */
    @Test
    void verifyModuleBoundaries() {
        ApplicationModules modules = ApplicationModules.of(OutcomeProbeServerApplication.class);

        assertThatCode(modules::verify).doesNotThrowAnyException();
    }
}
