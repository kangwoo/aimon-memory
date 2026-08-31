import com.diffplug.gradle.spotless.SpotlessExtension
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.springDepMgmt) apply false
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "dev.dyad"
    version = "0.1.0-SNAPSHOT"
}

// Resolved once at the root; subprojects never touch the catalog accessor directly.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun ver(alias: String): String = catalog.findVersion(alias).orElseThrow().requiredVersion

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(ver("java").toInt())) }
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${ver("springBoot")}")
            mavenBom("org.testcontainers:testcontainers-bom:${ver("testcontainers")}")
        }
    }

    dependencies {
        "implementation"("org.slf4j:slf4j-api")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Above the JAVA_TOOL_OPTIONS floor some developer machines set; the default 512m loses to it.
        maxHeapSize = "2g"
        systemProperty("file.encoding", "UTF-8")
        // One fixture corpus for every module, wherever Gradle happens to set the working directory.
        systemProperty("dyad.fixtures.dir", rootProject.layout.projectDirectory.dir("test-fixtures").asFile.absolutePath)
        systemProperty("dyad.golden.update", System.getProperty("dyad.golden.update") ?: "false")
        systemProperty("dyad.eval.update", System.getProperty("dyad.eval.update") ?: "false")
        systemProperty("dyad.load", System.getProperty("dyad.load") ?: "false")
        // CI never talks to a live model. Overriding this is a local, manual act.
        environment("DYAD_LLM_MODE", System.getenv("DYAD_LLM_MODE") ?: "replay")
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
