package io.outcomeprobe.scenario.builtin;

import io.outcomeprobe.scenario.ArtifactImportService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 서버 시작 시 로컬 데모용 Target Profile만 import한다.
 *
 * <p>책임: 외부 credential 없이 격리된 검증 대상을 선택할 수 있도록 두 Profile을 등록한다.</p>
 * <p>핵심 불변식: Contract와 Scenario는 사용자나 검증 harness가 명시적으로 import하며,
 * 서버 classpath에서 자동 등록하지 않는다.</p>
 */
@Component
public class BuiltInArtifactSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltInArtifactSeeder.class);
    private static final String PROFILE_SEEDING_ENABLED_PROPERTY =
            "outcomeprobe.demo.profile-seeding-enabled";
    private static final String VULNERABLE_BASE_URI_PROPERTY =
            "outcomeprobe.demo.vulnerable-base-uri";
    private static final String RESILIENT_BASE_URI_PROPERTY =
            "outcomeprobe.demo.resilient-base-uri";
    private static final String DEFAULT_VULNERABLE_BASE_URI =
            "http://reference-consumer-vulnerable:8091";
    private static final String DEFAULT_RESILIENT_BASE_URI =
            "http://reference-consumer-resilient:8092";

    private final ArtifactImportService artifactImportService;
    private final Environment environment;

    /**
     * import 정책 서비스를 주입한다.
     *
     * @param artifactImportService Artifact import 정책 서비스
     * @param environment 데모 Profile 등록 여부와 대상 URI를 제공하는 실행 환경
     */
    public BuiltInArtifactSeeder(ArtifactImportService artifactImportService,
                                 Environment environment) {
        this.artifactImportService = artifactImportService;
        this.environment = environment;
    }

    /**
     * 데모 Target Profile 두 개를 순서대로 import한다.
     *
     * @param args 애플리케이션 실행 인수
     */
    @Override
    public void run(ApplicationArguments args) {
        boolean profileSeedingEnabled = environment.getProperty(
                PROFILE_SEEDING_ENABLED_PROPERTY, Boolean.class, false);
        if (!profileSeedingEnabled) {
            log.info("데모 Target Profile 자동 등록을 비활성화했습니다.");
            return;
        }

        String vulnerableBaseUri = environment.getProperty(
                VULNERABLE_BASE_URI_PROPERTY, DEFAULT_VULNERABLE_BASE_URI);
        String resilientBaseUri = environment.getProperty(
                RESILIENT_BASE_URI_PROPERTY, DEFAULT_RESILIENT_BASE_URI);

        seedProfile("builtin/profiles/vulnerable-consumer.yaml", vulnerableBaseUri);
        seedProfile("builtin/profiles/resilient-consumer.yaml", resilientBaseUri);
    }

    /**
     * 내장 Target Profile의 base URI를 주입한 뒤 import한다.
     *
     * @param resourcePath classpath resource 경로
     * @param baseUri 격리된 대상 서비스의 base URI
     */
    private void seedProfile(String resourcePath, String baseUri) {
        String yaml = readResource(resourcePath).replace("{{BASE_URI}}", baseUri);
        try {
            artifactImportService.importArtifact(yaml, true);
        } catch (RuntimeException e) {
            log.warn("내장 profile import를 건너뜁니다({}): {}", resourcePath, e.getMessage());
        }
    }

    /**
     * classpath resource를 UTF-8 문자열로 읽는다.
     *
     * @param resourcePath 읽을 resource 경로
     * @return UTF-8 resource 본문
     */
    private String readResource(String resourcePath) {
        try (InputStream stream = new ClassPathResource(resourcePath).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("내장 artifact 리소스를 읽을 수 없습니다: " + resourcePath, e);
        }
    }
}
