package org.outcomeprobe.referenceconsumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 취약 정책 profile에서 Reference Consumer가 정상 기동되는지 검증한다.
 *
 * <p>검출 가능한 결함: profile별 port·설정 오류로 데모 취약 대상 컨테이너가
 * 시작조차 실패하는 문제.</p>
 */
@SpringBootTest(properties = "OUTCOMEPROBE_DEMO_CALLBACK_SECRET=context-test-secret")
@ActiveProfiles("vulnerable")
class VulnerableContextTest {

    /**
     * 취약 정책 컨텍스트가 로드돼야 데모 실패 재현 대상이 실행 가능하다.
     */
    @Test
    void loadsContext() {
    }
}
