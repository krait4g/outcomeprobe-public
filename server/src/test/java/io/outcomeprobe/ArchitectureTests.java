package io.outcomeprobe;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * ArchUnit으로 강제하는 아키텍처 경계 테스트다.
 *
 * <p>책임: Hexagonal Architecture의 핵심 규칙을 컴파일 타임이 아닌 테스트 타임에
 * 지속 검증한다. 도메인·애플리케이션 코드가 Spring·HTTP·SQL에 결합되거나,
 * Controller가 persistence를 직접 참조하는 회귀를 검출한다.</p>
 * <p>검출 가능한 결함: 도메인의 Spring 의존, 도메인 내부의 시스템 시계·전역 난수
 * 직접 호출, Controller의 영속성 패키지 참조.</p>
 * <p>M0 시점에는 대상 패키지가 비어 있으므로 규칙이 빈 집합으로 통과한다.
 * {@code allowEmptyShould(true)}는 빈 집합 통과만 허용할 뿐, 클래스가 등장한 뒤
 * 위반하면 계속 실패한다.</p>
 */
class ArchitectureTests {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.outcomeprobe");

    /**
     * 도메인·애플리케이션 패키지가 프레임워크와 외부 프로토콜 타입에 의존하지 못하게 한다.
     *
     * <p>모듈이 추가되는 순간부터 즉시 강제된다.</p>
     */
    @Test
    void domainMustNotDependOnFrameworks() {
        noClasses()
                .that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.servlet..",
                        "jakarta.persistence..",
                        "java.sql..",
                        "javax.sql..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "org.apache.hc..",
                        "java.net.http..")
                .allowEmptyShould(true)
                .check(classes);
    }

    /**
     * 도메인·애플리케이션 코드가 시스템 시계와 전역 난수를 직접 호출하지 못하게 한다.
     *
     * <p>결정론적 Scenario 재현을 깨뜨리는 {@code Instant.now()}·{@code UUID.randomUUID()}
     * 직접 호출을 검출한다. 허용된 호출 지점은 foundation.support 구현뿐이다.</p>
     */
    @Test
    void domainMustNotCallSystemClockOrGlobalRandom() {
        noClasses()
                .that().resideInAnyPackage("..domain..", "..application..")
                .should(callMethodWhere(
                        target(owner(belongToAnyOf(Instant.class, UUID.class)))
                                .and(target(name("now")).or(target(name("randomUUID"))))))
                .allowEmptyShould(true)
                .check(classes);
    }

    /**
     * Controller와 Control API 모듈이 영속성 패키지를 직접 참조하지 못하게 한다.
     *
     * <p>업무 규칙이 Controller로 새어 나가거나 Persistence Entity가 API 응답으로
     * 직접 반환되는 구조를 조기에 차단한다.</p>
     */
    @Test
    void controllersMustNotDependOnPersistence() {
        noClasses()
                .that().resideInAnyPackage("..interfaceapi..")
                .or().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter.out.persistence..",
                        "..persistence..")
                .check(classes);
    }

    /**
     * inbound adapter가 outbound adapter를 직접 호출하지 못하게 한다.
     *
     * <p>웹 계층이 영속성·외부送信 어댑터를 우회하면 Hexagonal 경계가 무의미해진다.</p>
     */
    @Test
    void inboundAdaptersMustNotCallOutboundAdapters() {
        noClasses()
                .that().resideInAnyPackage("..adapter.in..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.out..")
                .check(classes);
    }

    /**
     * 다른 모듈의 execution persistence adapter를 직접 참조하지 못하게 한다.
     *
     * <p>모듈 간 직접 테이블 접근 금지를 ArchUnit으로 강제한다.
     * execution 모듈 내부의 application→adapter 의존은 설계상 허용된다.</p>
     */
    @Test
    void otherModulesMustNotTouchExecutionPersistence() {
        noClasses()
                .that().resideInAnyPackage(
                        "io.outcomeprobe.scenario..",
                        "io.outcomeprobe.emulator..",
                        "io.outcomeprobe.delivery..",
                        "io.outcomeprobe.probe..",
                        "io.outcomeprobe.report..",
                        "io.outcomeprobe.interfaceapi..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.outcomeprobe.execution.adapter.out.persistence..")
                .check(classes);
    }
}
