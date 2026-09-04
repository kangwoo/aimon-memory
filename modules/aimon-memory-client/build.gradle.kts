// The adapter aimon-core consumes: `at.aimon.core.memory.PeerMemory` over this service's HTTP API.
//
// It depends on aimon-core and on nothing else in this build. That is deliberate — an application that wants remote
// memory should not end up with pgvector, Flyway, Lucene and a Spring Boot application on its classpath, and nothing
// in the five tiers needs them.
plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

// Java 17 bytecode, because that is aimon-core's floor and this is the one module it compiles against.
// `--release` rather than a second toolchain: it pins the *API* as well as the class file version, so a Java 21
// method cannot slip in and fail at the consumer instead of here, and it needs no extra JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(libs.findVersion("javaCompat").orElseThrow().requiredVersion.toInt())
}

dependencies {
    // `api`, not `implementation`: every tier this module implements is an aimon-core type, so the contract is on
    // the consumer's compile classpath by definition.
    api(libs.findLibrary("aimon-core").get())
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(libs.findLibrary("assertj").get())
}

/**
 * Refuses to publish against an aimon-core that does not contain the API this module implements.
 *
 * This module was written before any release had `PeerMemory`: at.aimon.core:aimon-core:0.2.3 carried the older
 * ObservationStore generation, so the build compiled against a sibling checkout through a composite build, and
 * publishing then would have shipped a POM pointing at an artifact no consumer could load. 0.2.4 has the five tiers,
 * the composite is gone, and this check now passes on its own.
 *
 * It stays because the failure it names is not a thing that happened once. Both ways back into it are one line: an
 * `includeBuild` in settings.gradle.kts, which resolves the coordinate to a *project* and makes a green build a
 * measurement of a working tree nobody can download; or a downgrade of `aimonCore` below 0.2.4. The check asks the
 * resolution result which of the two answered, and opens the jar to be sure the class is actually in it.
 *
 * Deliberately not a detached configuration resolving the coordinate by hand. That was the first attempt and it
 * reported success even against 0.2.3: composite substitution applies to detached configurations too, so the check
 * downloaded nothing and inspected the same local checkout it was written to catch.
 */
val verifyCoreIsReleased by tasks.registering {
    description = "Fails if the aimon-core this module is built against is a local checkout or lacks PeerMemory."
    group = "verification"
    outputs.upToDateWhen { false }

    val classpath = configurations.named("compileClasspath")

    doLast {
        val resolution = classpath.get().incoming.resolutionResult
        val core = resolution.allComponents.firstOrNull { it.moduleVersion?.name == "aimon-core" }
            ?: throw GradleException("aimon-core is not on this module's compile classpath at all")

        val id = core.id
        if (id !is ModuleComponentIdentifier) {
            throw GradleException(
                "aimon-core resolved to $id — a project rather than a released artifact, which means something has " +
                    "put an `includeBuild` back in settings.gradle.kts. Publishing aimon-memory-client from that " +
                    "build would ship a POM pointing at an aimon-core nobody else has.",
            )
        }

        val jar = classpath.get().resolvedConfiguration.resolvedArtifacts
            .firstOrNull { it.moduleVersion.id.name == "aimon-core" }?.file
            ?: throw GradleException("aimon-core resolved to $id but produced no artifact to inspect")
        val hasPeerMemory = zipTree(jar).matching { include("at/aimon/core/memory/PeerMemory.class") }
            .files.isNotEmpty()
        if (!hasPeerMemory) {
            throw GradleException(
                "$id does not contain at.aimon.core.memory.PeerMemory, so publishing aimon-memory-client against " +
                    "it would ship an artifact no consumer can load. 0.2.4 is the first release that has it.",
            )
        }
        logger.lifecycle("$id is a released artifact and contains PeerMemory")
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyCoreIsReleased)
}
