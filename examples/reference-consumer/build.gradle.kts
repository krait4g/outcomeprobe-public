plugins {
    alias(libs.plugins.spring.boot)
}

// 실행 jar는 고정 파일명으로 만들어 Docker COPY가 plain jar와 혼동하지 않게 한다.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "reference-consumer.jar"
}

dependencies {
    implementation(platform(libs.spring.boot.bom))

    implementation("org.springframework.boot:spring-boot-starter-web")
    // Boot 4에서 web starter는 JSON을 기본 포함하지 않으므로 명시적으로 추가한다.
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
