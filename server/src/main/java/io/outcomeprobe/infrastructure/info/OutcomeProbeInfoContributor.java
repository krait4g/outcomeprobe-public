package io.outcomeprobe.infrastructure.info;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * {@code /actuator/info}에 OutcomeProbe 제품 메타데이터를 노출한다.
 *
 * <p>책임: 이 서버가 production gateway가 아닌 local·test·CI 전용 워크벤치임을
 * info 응답에 명시해 운영자가 배포 성격을 오해하지 않게 한다.</p>
 * <p>핵심 불변식: {@code productionGateway} 값은 항상 false로 보고한다.
 * 이 값을 true로 바꾸는 구성 옵션은 v1에 존재하지 않는다.</p>
 */
public class OutcomeProbeInfoContributor implements InfoContributor {

    private final String version;

    /**
     * 보고할 서버 버전을 주입한다.
     *
     * @param version 빌드 또는 구성에서 제공되는 서버 버전 문자열
     */
    public OutcomeProbeInfoContributor(@Value("${outcomeprobe.version:0.1.0}") String version) {
        this.version = version;
    }

    /**
     * info 응답의 {@code outcomeprobe} 항목에 제품 식별 정보를 추가한다.
     *
     * <p>지원 DSL 버전, 저장소 종류, 실행 모드, production gateway 여부를 함께 노출한다.</p>
     *
     * @param builder Spring Actuator가 제공하는 info 구성 빌더
     */
    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", version);
        details.put("dslVersions", List.of("outcomeprobe.io/v1alpha1"));
        details.put("storage", "postgresql");
        details.put("mode", "local-test-workbench");
        details.put("productionGateway", false);
        details.put("releaseFlavor", "core-only");
        details.put("packSupport", false);
        builder.withDetail("outcomeprobe", details);
    }
}
