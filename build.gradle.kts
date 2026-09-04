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
}

tasks.register("integrationTest") {
    description = "Run the Testcontainers tier in every module"
    group = "verification"
    dependsOn(codeSubprojects().map { it.tasks.named("integrationTest") })
}
