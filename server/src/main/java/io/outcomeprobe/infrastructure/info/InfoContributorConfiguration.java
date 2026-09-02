package io.outcomeprobe.infrastructure.info;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * info 기여자 bean을 등록하는 구성이다.
 *
 * <p>책임: Actuator info endpoint에 제품 메타데이터를 추가하는 기여자를 컨텍스트에 연결한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 구성은 health 판단이나 보안 검사를 수행하지 않는다.
 * 단순 메타데이터 노출만 담당한다.</p>
 */
@Configuration
public class InfoContributorConfiguration {

    /**
     * 기본 구성을 생성한다.
     */
    public InfoContributorConfiguration() {
    }

    /**
     * 서버 버전을 주입받아 OutcomeProbe info 기여자를 생성한다.
     *
     * @param version 구성 또는 기본값에서 제공하는 서버 버전
     * @return info 기여자 bean
     */
    @Bean
    public OutcomeProbeInfoContributor outcomeProbeInfoContributor(
            @Value("${outcomeprobe.version:0.1.0}") String version) {
        return new OutcomeProbeInfoContributor(version);
    }
}
