// Module-wide quality, packaging and publishing configuration lives in the pre-compiled script plugins
// under `buildSrc/src/main/kotlin/`:
//   - aimon.java-conventions  (Java 21, Spring/Testcontainers BOMs, Spotless, Checkstyle, JaCoCo, tiers)
//   - aimon.publishable       (Maven Central publishing via vanniktech)
//
// Each module opts in with `plugins { id("aimon.java-conventions") }` and, where it is published,
// `id("aimon.publishable")`.
plugins {
    java
    // Declared here without applying so `aimon-memory-api` and `-worker` can ask for it by id alone.
    //
    // `io.spring.dependency-management` is deliberately NOT declared alongside it: buildSrc already puts
    // that plugin on the build's classpath so `aimon.java-conventions` can apply it, and resolving the
    // same id again from the catalog fails with "already on the classpath with an unknown version".
    alias(libs.plugins.springBoot) apply false
}

allprojects {
    group = findProperty("GROUP") as String
    version = findProperty("VERSION_NAME") as String

    repositories {
        mavenCentral()
        // Paired with the same block in settings.gradle.kts — the reason is written out there. One coordinate
        // needs it, `at.aimon.core:aimon-memory-testkit`, and only the opt-in contract tier asks for that one.
        // Both blocks are needed and this is the one that wins: project repositories take precedence over the
        // settings block under Gradle's default `PREFER_PROJECT` mode.
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content { includeModule("at.aimon.core", "aimon-memory-testkit") }
        }
    }
}

// Aggregator tasks. Subprojects use the convention plugin, so `spotlessApply`, `spotlessCheck` and
// `checkstyleMain` are guaranteed to exist — for every subproject that has Java sources, that is.
// `aimon-memory-bom` is a `java-platform`, and Gradle refuses `java-platform` alongside the
// `java-library` the conventions apply, so it is the one project here with nothing to aggregate. It is
// excluded by asking what it is, not by name.
//
// Everything else is addressed with `tasks.named`, which fails loudly when the task is missing. That is
// the point: a new module that forgets `aimon.java-conventions` breaks the root build instead of quietly
// slipping past the gates. `matching { }` or a `withType` sweep would have made that omission invisible.
fun codeSubprojects(): List<Project> = subprojects.filterNot { it.plugins.hasPlugin("java-platform") }

tasks.register("format") {
    description = "Format all Java code using Spotless"
    group = "formatting"
    dependsOn(codeSubprojects().map { it.tasks.named("spotlessApply") })
}

tasks.register("checkFormat") {
    description = "Check Java code formatting using Spotless"
    group = "verification"
    dependsOn(codeSubprojects().map { it.tasks.named("spotlessCheck") })
}

tasks.register("checkStyle") {
    description = "Run Checkstyle on all modules"
    group = "verification"
    dependsOn(codeSubprojects().map { it.tasks.named("checkstyleMain") })
}

// `test` here is each module's own test task, which excludes the `@Tag("docker")` tests (see
// aimon.java-conventions). Those stay opt-in via `integrationTest`, and they are where most of this
// system's behaviour is actually proven — a schema, a partial unique index and a pgvector distance are
// not things a mock stands in for. `checkAll` is the fast gate; the release gate runs both tiers.
tasks.register("checkAll") {
    description = "Run the fast gates (Spotless + Checkstyle + unit tests)"
    group = "verification"
    dependsOn("checkFormat", "checkStyle")
    dependsOn(codeSubprojects().map { it.tasks.named("test") })
    // The BOM has no tests, but it has a claim that can be wrong — that it manages exactly the modules
    // this build publishes — so the gate picks up its `verifyBom` in place of the test task it lacks.
    dependsOn(":aimon-memory-bom:verifyBom")
    // aimon-core's PeerMemory contract suite, which is a Test task of its own rather than part of
    // `aimon-memory-client`'s `test` — the artifact it subclasses is not on Central yet, so the source set
    // skips itself where it does not resolve (see that module's build file). Named here rather than left
    // out: on a machine that has the testkit the suite belongs in the gate, and on one that does not it
    // costs a skipped task and a line saying why.
    dependsOn(":aimon-memory-client:contractTest")
}

tasks.register("integrationTest") {
    description = "Run the Testcontainers tier in every module"
    group = "verification"
    dependsOn(codeSubprojects().map { it.tasks.named("integrationTest") })
}
