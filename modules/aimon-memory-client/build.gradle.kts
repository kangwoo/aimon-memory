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
 * The composite build in settings.gradle.kts substitutes a local aimon-core checkout, which is the only place
 * `PeerMemory` currently exists — at.aimon.core:aimon-core:0.2.3 on Central predates it. So this module compiles
 * green here and would publish a POM pointing at an artifact that cannot load it: every consumer would get a
 * NoClassDefFoundError on first use, and the first evidence would be on Central, permanently.
 *
 * The check asks the resolution result what actually answered the coordinate. A composite substitution resolves to
 * a *project*, not a module, and that alone is the condition to refuse on — it means the green build measured a
 * working tree that no consumer can download. A module that resolved normally is then opened and checked for the
 * class, which catches the other case: a released aimon-core that is simply too old.
 *
 * Deliberately not a detached configuration resolving the coordinate by hand. That was the first attempt and it
 * reported success: composite substitution applies to detached configurations too, so the check downloaded nothing
 * and inspected the same local checkout it was written to catch.
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
                "aimon-core resolved to $id — a local checkout substituted by the composite build in " +
                    "settings.gradle.kts, not a released artifact. Publishing aimon-memory-client now would ship a " +
                    "POM pointing at an aimon-core that cannot load it. Release an aimon-core containing the " +
                    "five-tier PeerMemory API, bump `aimonCore` in gradle/libs.versions.toml, and build with " +
                    "-PaimonCoreDir=none.",
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
                    "it would ship an artifact no consumer can load. Bump `aimonCore` in " +
                    "gradle/libs.versions.toml to a release that has it.",
            )
        }
        logger.lifecycle("$id is a released artifact and contains PeerMemory")
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyCoreIsReleased)
}
