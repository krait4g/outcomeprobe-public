package org.outcomeprobe.referenceconsumer;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reference Consumer의 공통 구성이다.
 *
 * <p>책임: 복원력 정책의 background 복구 loop를 위해 스케줄링을 활성화한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 취약 profile에서도 스케줄링은 활성화되지만
 * 복구 loop bean 자체가 등록되지 않아 아무 일도 일어나지 않는다.</p>
 */
@Configuration
@EnableScheduling
public class ReferenceConsumerConfiguration {

    /**
     * 기본 구성을 생성한다.
     */
    public ReferenceConsumerConfiguration() {
    }
}
