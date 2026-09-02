package io.outcomeprobe.foundation;

import java.time.Clock;
import java.time.Instant;

/**
 * 실행 시각을 제공하는 포트이다.
 *
 * <p>책임: 도메인과 애플리케이션 코드가 시스템 시계에 직접 접근하지 못하게 막고,
 * 테스트에서는 결정론적인 fake clock으로 대체할 수 있는 주입 지점을 제공한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 포트는 Scenario 계획의 논리 시간(logical time)을
 * 계산하지 않는다. 논리 시간은 Scenario compiler가 seed 기반으로 별도 관리한다.</p>
 */
public interface ClockProvider {

    /**
     * 현재 관측 시각을 UTC 기준으로 반환한다.
     *
     * <p>Evidence의 observed_at과 lease 만료 판단처럼 실제 경과 시간이 필요한 지점에서만 사용한다.</p>
     *
     * @return UTC 기준 현재 시각
     */
    Instant now();

    /**
     * 이 포트가 감싸고 있는 clock을 반환한다.
     *
     * <p>duration 계산처럼 clock 자체가 필요한 하위 코드에 그대로 전달한다.</p>
     *
     * @return UTC에 고정된 clock
     */
    Clock clock();
}
