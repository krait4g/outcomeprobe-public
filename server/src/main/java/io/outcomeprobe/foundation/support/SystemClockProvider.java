package io.outcomeprobe.foundation.support;

import io.outcomeprobe.foundation.ClockProvider;
import java.time.Clock;
import java.time.Instant;

/**
 * 시스템 UTC 시계를 사용하는 기본 {@link ClockProvider} 구현이다.
 *
 * <p>책임: 운영·로컬 실행에서 실제 시각을 제공한다. 테스트에서는 이 구현 대신
 * 고정 clock을 주입해 결정론을 유지한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 시스템 시계는 NTP 보정이나 사용자 설정으로
 * 되감길 수 있다. lease 만료와 deadline 계산은 이 clock의 단조성을 가정하지 않고
 * PostgreSQL 서버 시각과 함께 판단한다.</p>
 */
public final class SystemClockProvider implements ClockProvider {

    private final Clock clock;

    /**
     * UTC 시스템 시계를 감싸는 제공자를 생성한다.
     */
    public SystemClockProvider() {
        this.clock = Clock.systemUTC();
    }

    /**
     * 테스트나 대체 구현을 위해 명시적인 clock을 주입해 생성한다.
     *
     * @param clock 이 제공자가 사용할 clock
     */
    public SystemClockProvider(Clock clock) {
        this.clock = clock;
    }

    /**
     * 주입된 clock의 현재 시각을 반환한다.
     *
     * @return UTC 기준 현재 시각
     */
    @Override
    public Instant now() {
        return clock.instant();
    }

    /**
     * 주입된 clock을 그대로 반환한다.
     *
     * @return 이 제공자가 사용하는 clock
     */
    @Override
    public Clock clock() {
        return clock;
    }
}
