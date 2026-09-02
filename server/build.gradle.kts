plugins {
    alias(libs.plugins.spring.boot)
}

// 실행 jar는 고정 파일명으로 만들어 Docker COPY가 plain jar와 혼동하지 않게 한다.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "outcomeprobe-server.jar"
    from(rootProject.files("LICENSE")) {
        into("META-INF/licenses")
        rename { "OUTCOMEPROBE-LICENSE" }
    }
    from(rootProject.files("THIRD-PARTY-NOTICES", "THIRD-PARTY-LICENSES.json")) {
        into("META-INF/licenses")
    }
    exclude(
        "builtin/generic-payment",
        "builtin/generic-payment/**",
        "builtin/generic-booking",
        "builtin/generic-booking/**",
        "builtin/generic-webhook",
        "builtin/generic-webhook/**",
    )
    manifest {
        attributes(
            "Implementation-Title" to "OutcomeProbe Server Core",
            "Implementation-Version" to project.version,
            "OutcomeProbe-Release-Flavor" to "core-only",
            "OutcomeProbe-Pack-Support" to "false",
        )
    }
}

val integrationTest = sourceSets.register("integrationTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // Boot 4에서 Flyway 자동 구성은 별도 모듈이다.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Boot 4는 Jackson 3(tools.jackson)를 사용한다.
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation(libs.json.path)
    implementation(libs.json.schema.validator)

    // JSONB 매핑을 persistence adapter에서 명시적으로 다루므로 컴파일 시에도 필요하다.
    implementation(libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-test")
    testImplementation(libs.archunit)

    "integrationTestImplementation"(libs.testcontainers.postgresql)
    "integrationTestImplementation"(libs.testcontainers.junit.jupiter)
}

// Docker 없이 로컬에서 전체 시나리오를 검증하는 E2E 소스셋이다. 내장 PostgreSQL을 사용한다.
val localE2eTest = sourceSets.register("localE2eTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations["localE2eTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["localE2eTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "localE2eTestImplementation"(project(":examples:reference-consumer"))
    "localE2eTestImplementation"(libs.zonky.embedded.postgres)
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Testcontainers PostgreSQL이 필요한 통합 테스트를 실행한다. Docker가 필요하다."
    testClassesDirs = integrationTest.get().output.classesDirs
    classpath = integrationTest.get().runtimeClasspath
    useJUnitPlatform()
    // 통합 회귀는 production 기본값과 달리 test-only 데모 Profile을 명시적으로 켠다.
    systemProperty("outcomeprobe.demo.profile-seeding-enabled", "true")
    systemProperty(
        "outcomeprobe.security.control-api-token",
        "integration-test-control-token-0001",
    )
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("localE2eTest") {
    group = "verification"
    description = "내장 PostgreSQL과 in-process Reference Consumer로 시나리오 전체를 실행 검증한다. Docker가 필요 없다."
    testClassesDirs = localE2eTest.get().output.classesDirs
    classpath = localE2eTest.get().runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}
