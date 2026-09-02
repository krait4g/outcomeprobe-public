plugins {
    application
    alias(libs.plugins.shadow)
}

application {
    mainClass = "io.outcomeprobe.cli.OutcomeProbeCli"
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.picocli)
    // Boot 4는 Jackson 3(tools.jackson)를 사용한다.
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName = "outcomeprobe-cli"
    archiveClassifier = ""
    mergeServiceFiles()
    from(rootProject.files("LICENSE")) {
        into("META-INF/licenses")
        rename { "OUTCOMEPROBE-LICENSE" }
    }
    from(rootProject.files("THIRD-PARTY-NOTICES", "THIRD-PARTY-LICENSES.json")) {
        into("META-INF/licenses")
    }
    manifest {
        attributes(
            "Implementation-Title" to "OutcomeProbe CLI Core",
            "Implementation-Version" to project.version,
            "OutcomeProbe-Release-Flavor" to "core-only",
            "OutcomeProbe-Pack-Support" to "false",
        )
    }
}
