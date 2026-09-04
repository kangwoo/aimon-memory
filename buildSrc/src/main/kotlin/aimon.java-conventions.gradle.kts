import com.diffplug.gradle.spotless.SpotlessExtension
// Imported rather than fully qualified: inside a .gradle.kts, `java` resolves to the JavaPluginExtension
// accessor and shadows the package root, so `java.util.Properties` does not compile.
import java.math.BigDecimal
import java.util.Properties
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    checkstyle
    jacoco
    id("com.diffplug.spotless")
    id("io.spring.dependency-management")
}

@Suppress("UnstableApiUsage")
val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

fun version(alias: String): String = libs.findVersion(alias).orElseThrow().requiredVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(version("java").toInt()))
    }
}

// Spring Boot and Testcontainers manage the versions of everything they ship, so no module here declares
// one inline. The catalog holds what those two do not.
configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${version("springBoot")}")
        mavenBom("org.testcontainers:testcontainers-bom:${version("testcontainers")}")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    isFailOnError = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
    // A worker daemon inherits JAVA_TOOL_OPTIONS from the shell, and Gradle then passes its own smaller
    // -Xmx on the command line — which overrides the inherited -Xmx but NOT the inherited -Xms. So
    // `JAVA_TOOL_OPTIONS=-Xmx4g -Xms1g` lands as "-Xms1g with a max below 1g" and the worker dies before
    // javac starts, with nothing wrong in the source. CI never sees it; a contributor's machine does.
    options.isFork = true
    options.forkOptions.memoryInitialSize = "256m"
    options.forkOptions.memoryMaximumSize = "2g"
}

checkstyle {
    toolVersion = version("checkstyle")
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

tasks.named<Checkstyle>("checkstyleMain") {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Test sources are exempt from Checkstyle, as they are in aimon-core.
tasks.named<Checkstyle>("checkstyleTest") {
    enabled = false
}

configure<SpotlessExtension> {
    java {
        target("src/**/*.java")
        eclipse().configFile(rootProject.file("config/eclipse/eclipse-formatter.xml"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org", "com", "")
        toggleOffOn()
    }

    format("misc") {
        target("*.gradle.kts", "*.md", ".gitignore")
        trimTrailingWhitespace()
        indentWithSpaces(2)
        endWithNewline()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Above the JAVA_TOOL_OPTIONS floor some developer machines set; the default 512m loses to it.
    minHeapSize = "256m"
    maxHeapSize = "2g"
    systemProperty("file.encoding", "UTF-8")
    // One fixture corpus for every module, wherever Gradle happens to set the working directory.
    systemProperty(
        "aimon.memory.fixtures.dir",
        rootProject.layout.projectDirectory.dir("test-fixtures").asFile.absolutePath,
    )
    systemProperty("aimon.memory.golden.update", System.getProperty("aimon.memory.golden.update") ?: "false")
    systemProperty("aimon.memory.eval.update", System.getProperty("aimon.memory.eval.update") ?: "false")
    systemProperty("aimon.memory.load", System.getProperty("aimon.memory.load") ?: "false")
    // CI never talks to a live model. Overriding this is a local, manual act.
    environment("AIMON_MEMORY_LLM_MODE", System.getenv("AIMON_MEMORY_LLM_MODE") ?: "replay")
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    reports {
        html.required.set(true)
        junitXml.required.set(true)
    }
}

// Testcontainers-backed tests are tagged `@Tag("docker")`. The default `test` task — the one `build` and
// `check` run — excludes them, so the loop a developer runs on every save needs no Docker daemon; the
// opt-in `integrationTest` task runs exactly those.
//
// Out of `test` is not the same as out of CI: `integrationTest` is a job of its own, and it is where
// almost all of this system's behaviour is actually proven. A schema, a partial unique index and a
// pgvector distance are not things a mock can stand in for.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("docker")
    }
}

val testSourceSet = the<SourceSetContainer>()["test"]
tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers integration tests (JUnit @Tag(\"docker\"))."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("docker")
    }
    shouldRunAfter(tasks.named("test"))
}

// The report reads every tier's execution data, not just `test`'s. Most of this build's coverage comes
// from the docker tier, so the plugin's default of `test.exec` alone would measure the store, worker and
// api modules with their tests excluded — a number that reads as "untested" when it means "not run".
//
// `mustRunAfter` rather than `dependsOn` for the opt-in tier: generating a report must not start
// requiring a Docker daemon.
tasks.withType<JacocoReportBase>().configureEach {
    dependsOn(tasks.named("test"))
    mustRunAfter(tasks.named("integrationTest"))
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
}

tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// The coverage floor. Values live in gradle/coverage-baselines.properties — data as data, so moving a
// number is a one-line diff a reviewer can read. A module with no entry gets no rule rather than a floor
// of zero: zero always passes, which reads as "verified" in the task list and verifies nothing.
val coverageBaselines = Properties().apply {
    val file = rootProject.file("gradle/coverage-baselines.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

coverageBaselines.getProperty(project.name)?.let { floor ->
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        description = "Fails if line coverage dropped below gradle/coverage-baselines.properties. Needs " +
            "every tier's execution data: run `test integrationTest` first, or the docker-backed modules " +
            "measure near zero."
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal(floor.trim()).divide(BigDecimal(100))
                }
            }
        }
    }
}

dependencies {
    "implementation"("org.slf4j:slf4j-api")
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.assertj:assertj-core")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
