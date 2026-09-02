import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Zip
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.shadow) apply false
}

// version catalog 접근자는 subprojects 블록 안에서 해석되지 않으므로 최상위에서 값을 고정한다.
val checkstyleToolVersion = libs.versions.checkstyle.get()

allprojects {
    group = "io.outcomeprobe"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            showStandardStreams = false
        }
    }

    configure<CheckstyleExtension> {
        toolVersion = checkstyleToolVersion
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    }

    // Gradle 9는 JUnit Platform launcher를 자동 주입하지 않으므로 각 모듈이 직접 선언한다.
    // 버전은 모듈별 spring-boot-dependencies BOM이 관리한다.
    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}

// 릴리스 후보 archive는 checkout 위치와 실행 시각에 영향을 받지 않도록 metadata를 정규화한다.
allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val coreReleaseVersion = "0.1.0"
val coreReleaseDirectory = layout.buildDirectory.dir("core-release")
val coreProjectLicenseFile = "LICENSE"
val coreProjectJarLicenseEntry = "OUTCOMEPROBE-LICENSE"
val coreLicenseFiles = listOf("THIRD-PARTY-NOTICES", "THIRD-PARTY-LICENSES.json")
val coreLicenseRegistryFile = "scripts/core-release-staging/spdx-license-registry-3.28.0.json"
val coreGoldenPathFiles = listOf(
    "examples/golden-path/payment/provider-contract.yaml",
    "examples/golden-path/payment/target-profile-vulnerable.yaml",
    "examples/golden-path/payment/target-profile-resilient.yaml",
    "examples/golden-path/payment/scenario.yaml",
    "examples/golden-path/payment/scenario-callback-duplicate.yaml",
)
val coreUserServiceKitFiles = listOf(
    "docs/integration-guide.md",
    "docs/adr/0020-user-service-connection-kit.md",
    ".env.secrets.example",
    "compose.secrets-directory.yaml",
    "compose.realtime-track.yaml",
    "docker/realtime-kit.Dockerfile",
    "docker/realtime-kit.sh",
    "examples/realtime-track/README.md",
    "examples/realtime-track/provider-contract.yaml",
    "examples/realtime-track/target-profile.compose.yaml",
    "examples/realtime-track/scenario-single-event.yaml",
    "examples/realtime-track/scenario-duplicate-3x.yaml",
    "examples/realtime-track/scenario-out-of-order.yaml",
    "examples/realtime-track/target-fixture/Dockerfile",
    "examples/realtime-track/target-fixture/realtime_track_target.py",
    "scripts/user-service-kit/create_user_service_kit_summary.py",
    "scripts/user-service-kit/tests/test_create_user_service_kit_summary.py",
    "scripts/user-service-kit/tests/test_realtime_target_fixture.py",
    "scripts/user-service-kit/verify-user-service-kit.sh",
)

fun InputStream.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) {
            break
        }
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun File.sha256Hex(): String = inputStream().use { it.sha256Hex() }

val verifyCoreReleaseSource by tasks.registering {
    group = "verification"
    description = "core-only source에서 배포 기능과 production 사본이 제거됐는지 fail-fast 검증한다."

    doLast {
        val forbiddenPaths = listOf(
            file("packs"),
            file("server/src/main/resources/builtin/generic-payment"),
            file("server/src/main/resources/builtin/generic-booking"),
            file("server/src/main/resources/builtin/generic-webhook"),
            file("server/src/main/java/io/outcomeprobe/pack"),
            file("server/src/localE2eTest/java/io/outcomeprobe/e2e/PackInventoryE2ETest.java"),
        )
        val present = forbiddenPaths.filter { path ->
            when {
                path.isDirectory -> path.walkTopDown().any { it.isFile }
                else -> path.exists()
            }
        }
        if (present.isNotEmpty()) {
            throw GradleException("core-only 금지 경로가 존재합니다: ${present.joinToString()}")
        }

        val cliSource = file("cli/src/main/java/io/outcomeprobe/cli/ImportCommand.java").readText()
        val forbiddenCliClaims = listOf("Scenario Pack", "Pack 디렉터리", "pack.yaml", "importPack")
        val cliHits = forbiddenCliClaims.filter { cliSource.contains(it, ignoreCase = true) }
        if (cliHits.isNotEmpty()) {
            throw GradleException("core CLI에 금지된 배포 기능 문구가 존재합니다: ${cliHits.joinToString()}")
        }
    }
}

val coreGitattributesSource = rootProject.layout.projectDirectory.file(".gitattributes")
val stagedCoreGitattributes = layout.buildDirectory.file("core-release/generated/gitattributes")
val stageCoreGitattributes by tasks.registering {
    inputs.file(coreGitattributesSource)
    outputs.file(stagedCoreGitattributes)
    doLast {
        val output = stagedCoreGitattributes.get().asFile
        output.parentFile.mkdirs()
        coreGitattributesSource.asFile.copyTo(output, overwrite = true)
    }
}

val coreSourceZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "shipping source만 allow-list로 묶은 core-only source archive를 만든다."
    dependsOn(verifyCoreReleaseSource, stageCoreGitattributes)
    archiveFileName.set("outcomeprobe-core-source-$coreReleaseVersion.zip")
    destinationDirectory.set(coreReleaseDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    includeEmptyDirs = false

    // Gradle/Ant 기본 exclude를 전역 변경하지 않고 점 파일 하나만 archive 이름으로 복원한다.
    from(stagedCoreGitattributes) {
        rename { ".gitattributes" }
    }

    from(rootDir) {
        include("LICENSE", "SECURITY.md", "CORE_RELEASE.md", "QUICKSTART.md")
        include(".dockerignore", ".env.example")
        include(*coreUserServiceKitFiles.toTypedArray())
        include(*coreLicenseFiles.toTypedArray())
        include(coreLicenseRegistryFile)
        include("settings.gradle.kts", "build.gradle.kts", "gradlew", "gradlew.bat")
        include("gradle/**", "config/**")
        include("server/build.gradle.kts", "server/src/main/**")
        include("cli/build.gradle.kts", "cli/src/main/**")
        include("examples/reference-consumer/build.gradle.kts", "examples/reference-consumer/src/main/**")
        include("examples/golden-path/payment/**")
        include("web/package.json", "web/package-lock.json", "web/index.html")
        include("web/tsconfig.json", "web/tsconfig.app.json", "web/tsconfig.node.json", "web/vite.config.ts")
        include("web/src/**")
        include("web/playwright.config.ts", "web/test/**", "web/e2e/**")
        include("compose.yaml")
        include(
            "docker/bootstrap.Dockerfile",
            "docker/bootstrap.sh",
            "docker/server.Dockerfile",
            "docker/reference-consumer.Dockerfile",
            "docker/web.Dockerfile",
            "docker/web.nginx.conf",
        )
        include("docker/staging/**")
    }
}

val stageCoreRelease by tasks.registering {
    group = "distribution"
    description = "검증된 server·CLI JAR와 source archive를 core-only staging 디렉터리에 모은다."
    dependsOn(":server:bootJar", ":cli:shadowJar", coreSourceZip)

    doLast {
        val stage = coreReleaseDirectory.get().dir("staging").asFile
        stage.deleteRecursively()
        stage.mkdirs()
        copy {
            from(project(":server").layout.buildDirectory.file("libs/outcomeprobe-server.jar"))
            into(stage)
            rename { "outcomeprobe-server-core-$coreReleaseVersion.jar" }
        }
        copy {
            from(project(":cli").layout.buildDirectory.file("libs/outcomeprobe-cli-$coreReleaseVersion.jar"))
            into(stage)
            rename { "outcomeprobe-cli-core-$coreReleaseVersion.jar" }
        }
        copy {
            from(coreSourceZip.flatMap { it.archiveFile })
            into(stage)
        }
    }
}

val verifyCoreReleaseArtifacts by tasks.registering {
    group = "verification"
    description = "staging JAR·source archive의 marker와 금지 경로 부재를 검증한다."
    dependsOn(stageCoreRelease)

    doLast {
        val stage = coreReleaseDirectory.get().dir("staging").asFile
        val expected = setOf(
            "outcomeprobe-server-core-$coreReleaseVersion.jar",
            "outcomeprobe-cli-core-$coreReleaseVersion.jar",
            "outcomeprobe-core-source-$coreReleaseVersion.zip",
        )
        val actual = stage.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
        if (actual != expected) {
            throw GradleException("core staging inventory 불일치: expected=$expected actual=$actual")
        }

        expected.filter { it.endsWith(".jar") }.forEach { name ->
            JarFile(File(stage, name)).use { jar ->
                val attributes = jar.manifest.mainAttributes
                val flavor = attributes.getValue("OutcomeProbe-Release-Flavor")
                val packSupport = attributes.getValue("OutcomeProbe-Pack-Support")
                val version = attributes.getValue("Implementation-Version")
                if (flavor != "core-only" || packSupport != "false" || version != coreReleaseVersion) {
                    throw GradleException(
                        "$name manifest 불일치: flavor=$flavor packSupport=$packSupport version=$version",
                    )
                }
                val projectLicenseEntryName = "META-INF/licenses/$coreProjectJarLicenseEntry"
                val projectLicenseEntries = jar.entries().asSequence()
                    .filter { it.name == projectLicenseEntryName }
                    .toList()
                if (projectLicenseEntries.size != 1) {
                    throw GradleException(
                        "$name project license entry count mismatch: " +
                            "$projectLicenseEntryName=${projectLicenseEntries.size}",
                    )
                }
                val embeddedProjectLicenseSha = jar.getInputStream(projectLicenseEntries.single())
                    .use { it.sha256Hex() }
                if (embeddedProjectLicenseSha != file(coreProjectLicenseFile).sha256Hex()) {
                    throw GradleException("$name project license SHA-256 mismatch: $projectLicenseEntryName")
                }
                coreLicenseFiles.forEach { policyName ->
                    val entryName = "META-INF/licenses/$policyName"
                    val entries = jar.entries().asSequence().filter { it.name == entryName }.toList()
                    if (entries.size != 1) {
                        throw GradleException("$name policy entry count mismatch: $entryName=${entries.size}")
                    }
                    val embeddedSha = jar.getInputStream(entries.single()).use { it.sha256Hex() }
                    if (embeddedSha != file(policyName).sha256Hex()) {
                        throw GradleException("$name policy SHA-256 mismatch: $entryName")
                    }
                }
            }
        }

        val forbiddenEntryPatterns = listOf(
            Regex("(^|/)packs?(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|/)pack\\.ya?ml$", RegexOption.IGNORE_CASE),
            Regex("(^|/)builtin/generic-(payment|booking|webhook)(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|/)io/outcomeprobe/pack(/|$)", RegexOption.IGNORE_CASE),
            Regex("PackInventoryE2ETest", RegexOption.IGNORE_CASE),
            Regex("(^|/)core-fixtures(/|$)", RegexOption.IGNORE_CASE),
        )
        expected.forEach { name ->
            ZipFile(File(stage, name)).use { archive ->
                val entries = archive.entries().asSequence().map { it.name }.toList()
                val invalid = entries.filter { entry -> forbiddenEntryPatterns.any { it.containsMatchIn(entry) } }
                if (invalid.isNotEmpty()) {
                    throw GradleException("$name 에 core-only 금지 entry가 있습니다: ${invalid.joinToString()}")
                }
                if (entries.size != entries.toSet().size) {
                    throw GradleException("$name 에 중복 archive entry가 있습니다.")
                }
                if (name.endsWith(".zip")) {
                    (listOf(coreProjectLicenseFile) + coreLicenseFiles + coreLicenseRegistryFile)
                        .forEach { policyName ->
                        val policyEntries = archive.entries().asSequence()
                            .filter { it.name == policyName }
                            .toList()
                        if (policyEntries.size != 1) {
                            throw GradleException(
                                "$name policy entry count mismatch: $policyName=${policyEntries.size}",
                            )
                        }
                        val embeddedSha = archive.getInputStream(policyEntries.single()).use { it.sha256Hex() }
                        if (embeddedSha != file(policyName).sha256Hex()) {
                            throw GradleException("$name policy SHA-256 mismatch: $policyName")
                        }
                    }
                    coreGoldenPathFiles.forEach { fixtureName ->
                        val fixtureEntries = archive.entries().asSequence()
                            .filter { it.name == fixtureName }
                            .toList()
                        if (fixtureEntries.size != 1) {
                            throw GradleException(
                                "$name golden-path entry count mismatch: $fixtureName=${fixtureEntries.size}",
                            )
                        }
                        val embeddedSha = archive.getInputStream(fixtureEntries.single()).use { it.sha256Hex() }
                        if (embeddedSha != file(fixtureName).inputStream().use { it.sha256Hex() }) {
                            throw GradleException("$name golden-path SHA-256 mismatch: $fixtureName")
                        }
                    }
                    coreUserServiceKitFiles.forEach { kitName ->
                        val kitEntries = archive.entries().asSequence()
                            .filter { it.name == kitName }
                            .toList()
                        if (kitEntries.size != 1) {
                            throw GradleException(
                                "$name user-service-kit entry count mismatch: $kitName=${kitEntries.size}",
                            )
                        }
                        val embeddedSha = archive.getInputStream(kitEntries.single()).use { it.sha256Hex() }
                        if (embeddedSha != file(kitName).inputStream().use { it.sha256Hex() }) {
                            throw GradleException("$name user-service-kit SHA-256 mismatch: $kitName")
                        }
                    }
                }
            }
        }
    }
}

val prepareCoreServerContext by tasks.registering {
    group = "distribution"
    description = "검증된 server JAR와 runtime-only Dockerfile만 포함한 최소 context를 만든다."
    dependsOn(verifyCoreReleaseArtifacts)

    doLast {
        val context = coreReleaseDirectory.get().dir("docker/server").asFile
        context.deleteRecursively()
        context.mkdirs()
        copy {
            from(file("docker/staging/server.Dockerfile"))
            into(context)
            rename { "Dockerfile" }
        }
        copy {
            from(coreReleaseDirectory.get().file("staging/outcomeprobe-server-core-$coreReleaseVersion.jar"))
            into(context)
        }
        copy {
            from((listOf(coreProjectLicenseFile) + coreLicenseFiles).map { file(it) })
            into(context)
        }
        val expected = setOf(
            "Dockerfile",
            "outcomeprobe-server-core-$coreReleaseVersion.jar",
            coreProjectLicenseFile,
            *coreLicenseFiles.toTypedArray(),
        )
        val actual = context.listFiles()?.map { it.name }?.toSet().orEmpty()
        if (actual != expected) {
            throw GradleException("server Docker context 불일치: expected=$expected actual=$actual")
        }
    }
}

tasks.register("coreReleaseCheck") {
    group = "verification"
    description = "core-only source·전체 JVM 회귀·staging artifact 경계를 한 번에 검증한다."
    dependsOn(
        "build",
        ":server:build",
        ":cli:build",
        ":examples:reference-consumer:build",
        ":server:integrationTest",
        ":server:localE2eTest",
        prepareCoreServerContext,
    )
}

val javaSourceRoots = listOf(
    "server/src",
    "cli/src",
    "examples/reference-consumer/src",
)

val tsSourceRoots = listOf("web/src")

val hangulPattern = Regex("[\uAC00-\uD7A3]")
val javaTypeDecl =
    Regex("""^\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(?:class|interface|enum|record|@interface)\s+(\w+)""")
val javaMemberDecl = Regex("""^\s*(public|protected|private)\b([^;{}()=]*?)\s(\w+)\s*\(""")
val javaControlKeyword = setOf("if", "for", "while", "switch", "catch", "return", "new", "throw", "assert", "super", "this")
val tsExportDecl = Regex("""^\s*export\s+(?:default\s+)?(?:async\s+)?(?:function|class|const|let|var|type|interface|enum)\b""")
val todoPattern = Regex("""TODO(?!\(OP-\d+\))""")

fun hasHangul(text: String): Boolean = hangulPattern.containsMatchIn(text)

fun collectPrecedingJavadoc(lines: List<String>, declIndex: Int): Pair<Boolean, Boolean> {
    var j = declIndex - 1
    // 선언과 Javadoc 사이의 어노테이션을 건너뛴다. 아래에서 위로 읽으므로 ')'가 먼저 보이면
    // 아직 닫히지 않은 어노테이션 인수 목록 안에 있다고 판단한다.
    var annotationParenDepth = 0
    while (j >= 0) {
        val trimmed = lines[j].trim()
        if (trimmed.isEmpty()) {
            j--
            continue
        }
        val opens = trimmed.count { it == '(' }
        val closes = trimmed.count { it == ')' }
        // 닫는 괄호가 더 많은 줄은 윗줄에서 열린 어노테이션을 닫는 줄이다.
        val isAnnotationPart = trimmed.startsWith("@") || annotationParenDepth > 0 || closes > opens
        if (!isAnnotationPart) {
            break
        }
        annotationParenDepth += closes - opens
        if (annotationParenDepth < 0) {
            annotationParenDepth = 0
        }
        j--
    }
    if (j < 0 || !lines[j].trimEnd().endsWith("*/")) {
        return Pair(false, false)
    }
    var k = j
    while (k >= 0 && !lines[k].contains("/**")) {
        k--
    }
    if (k < 0) {
        return Pair(true, false)
    }
    val block = lines.subList(k, j + 1).joinToString("\n")
    return Pair(true, hasHangul(block))
}

fun checkJavaFile(file: File, violations: MutableList<String>, warnings: MutableList<String>) {
    val lines = file.readLines()
    var depth = 0
    var interfaceMemberDepth = Int.MAX_VALUE

    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        val lineNo = index + 1
        val isCommentOnly = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")

        if (!isCommentOnly) {
            val typeMatch = javaTypeDecl.find(line)
            val isInterface = typeMatch != null && Regex("""\b(interface|@interface)\b""").containsMatchIn(line)
            val memberMatch = if (typeMatch == null) javaMemberDecl.find(line) else null
            val memberName = memberMatch?.groupValues?.get(3)
            val memberModifier = memberMatch?.groupValues?.get(1)
            // 접근 제한자와 이름 사이에 반환 타입이 없으면 생성자로 판단한다.
            val isConstructor = memberMatch != null && memberMatch.groupValues[2].isBlank()

            val requiresDoc = when {
                typeMatch != null -> true
                memberName != null && memberName !in javaControlKeyword -> true
                depth == interfaceMemberDepth && trimmed.endsWith(";") && trimmed.contains("(") &&
                    !trimmed.contains("=") && !trimmed.startsWith("@") -> true
                else -> false
            }

            if (requiresDoc) {
                val (hasDoc, hasKo) = collectPrecedingJavadoc(lines, index)
                // 명세 0.2.1: 명시적으로 선언한 모든 메소드(private·@Override 포함)에
                // 한국어 Javadoc을 강제한다. 감사 OP-AUDIT-HARN-001로 원복됨(ADR-0003 개정).
                val problem = when {
                    !hasDoc -> "'$trimmed' 선언 앞에 Javadoc이 없습니다."
                    !hasKo -> "'$trimmed' Javadoc에 한국어 설명이 없습니다."
                    else -> null
                }
                if (problem != null) {
                    violations.add("$file:$lineNo $problem")
                }
            }

            if (isInterface) {
                val openCount = line.count { it == '{' }
                val closeCount = line.count { it == '}' }
                if (openCount > closeCount) {
                    interfaceMemberDepth = depth + 1
                }
                depth += openCount - closeCount
            } else {
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth < interfaceMemberDepth) {
                    interfaceMemberDepth = Int.MAX_VALUE
                }
            }
        }

        if (trimmed.startsWith("/*") && !trimmed.startsWith("/**")) {
            violations.add("$file:$lineNo 일반 블록 주석(/*)은 금지됩니다. /** 또는 // 형식만 사용하세요.")
        }

        todoPattern.findAll(line).forEach {
            violations.add("$file:$lineNo TODO는 TODO(OP-번호) 형식이어야 합니다.")
        }

        val commentStart = findLineCommentStart(line)
        if (commentStart >= 0) {
            val comment = line.substring(commentStart + 2)
            if (comment.isNotBlank() && !hasHangul(comment) && !comment.contains("TODO(OP-")) {
                warnings.add("$file:$lineNo 라인 주석에 한국어가 없습니다: ${comment.trim()}")
            }
        }
    }
}

fun findLineCommentStart(line: String): Int {
    // TypeScript triple-slash 지시어(/// <reference .../>)는 라인 주석이 아니므로 검사하지 않는다.
    if (line.trimStart().startsWith("///")) {
        return -1
    }
    var index = line.indexOf("//")
    while (index >= 0) {
        val before = if (index > 0) line[index - 1] else ' '
        val insideUrl = before == ':'
        val insideJavadocLink = index > 0 && line[index - 1] == '*'
        if (!insideUrl && !insideJavadocLink) {
            return index
        }
        index = line.indexOf("//", index + 2)
    }
    return -1
}

fun checkTsFile(file: File, violations: MutableList<String>, warnings: MutableList<String>) {
    val lines = file.readLines()
    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trimEnd()
        val lineNo = index + 1
        if (tsExportDecl.containsMatchIn(line)) {
            val (hasDoc, hasKo) = collectPrecedingJavadoc(lines, index)
            when {
                !hasDoc -> violations.add("$file:$lineNo export 선언 앞에 TSDoc이 없습니다.")
                !hasKo -> violations.add("$file:$lineNo export 선언 TSDoc에 한국어 설명이 없습니다.")
                else -> Unit
            }
        }
        val commentStart = findLineCommentStart(line)
        if (commentStart >= 0) {
            val comment = line.substring(commentStart + 2)
            if (comment.isNotBlank() && !hasHangul(comment) && !comment.contains("TODO(OP-")) {
                warnings.add("$file:$lineNo 라인 주석에 한국어가 없습니다: ${comment.trim()}")
            }
        }
    }
}

tasks.register("verifyKoreanComments") {
    group = "verification"
    description = "모든 Java·TypeScript 소스의 한국어 Javadoc·라인 주석 규칙을 검증한다."

    doLast {
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        javaSourceRoots.forEach { root ->
            fileTree(root) { include("**/*.java") }.forEach { file ->
                checkJavaFile(file, violations, warnings)
            }
        }
        tsSourceRoots.forEach { root ->
            fileTree(root) { include("**/*.ts", "**/*.tsx") }.forEach { file ->
                checkTsFile(file, violations, warnings)
            }
        }

        warnings.forEach { logger.warn("[WARN] $it") }
        if (violations.isNotEmpty()) {
            violations.forEach { logger.error("[FAIL] $it") }
            throw GradleException("한국어 주석 품질 Gate 실패: 위반 ${violations.size}건")
        }
        logger.lifecycle("한국어 주석 품질 Gate 통과")
    }
}

tasks.register("verifyKoreanCommentsNegativeTest") {
    group = "verification"
    description = "한국어 주석 Gate가 위반을 실제로 검출하는지 부정 테스트로 확인한다."

    doLast {
        val tmpDir = layout.buildDirectory.dir("korean-gate-negative").get().asFile
        tmpDir.mkdirs()

        fun expectViolation(name: String, content: String, expectFail: Boolean) {
            val file = File(tmpDir, "$name.java")
            file.writeText(content, Charsets.UTF_8)
            val violations = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            checkJavaFile(file, violations, warnings)
            val detected = violations.isNotEmpty()
            if (detected != expectFail) {
                throw GradleException("부정 테스트 실패: $name (검출=$detected, 기대=$expectFail) $violations")
            }
            file.delete()
        }

        // private 메소드 Javadoc 제거 → 검출돼야 한다 (OP-AUDIT-HARN-001 원복 검증).
        expectViolation("PrivateNoDoc", """
            package x;
            public class PrivateNoDoc {
                private void noDoc() { }
            }
        """.trimIndent(), true)

        // @Override 메소드 Javadoc 제거 → 검출돼야 한다.
        expectViolation("OverrideNoDoc", """
            package x;
            public class OverrideNoDoc implements Runnable {
                @Override
                public void run() { }
            }
        """.trimIndent(), true)

        // 영어 Javadoc만 있는 경우 → 검출돼야 한다.
        expectViolation("EnglishDoc", """
            package x;
            public class EnglishDoc {
                /**
                 * English only javadoc.
                 */
                public void documented() { }
            }
        """.trimIndent(), true)

        // 일반 /* */ 블록 주석 → 검출돼야 한다.
        expectViolation("BlockComment", """
            package x;
            /* 일반 블록 주석 */
            public class BlockComment {
            }
        """.trimIndent(), true)

        // 한국어 Javadoc이 있는 정상 코드 → 검출되지 않아야 한다.
        expectViolation("GoodDoc", """
            package x;
            /**
             * 정상 한국어 문서 주석이 있는 클래스다.
             */
            public class GoodDoc {
                /**
                 * 한국어 문서 주석이 있는 메소드다.
                 */
                private void documented() { }
            }
        """.trimIndent(), false)

        logger.lifecycle("한국어 주석 Gate 부정 테스트 통과 (검출 4건 + 정상 1건)")
    }
}

tasks.named("check") {
    dependsOn("verifyKoreanComments")
    dependsOn("verifyKoreanCommentsNegativeTest")
}
