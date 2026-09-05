rootProject.name = "aimon-memory"

dependencyResolutionManagement {
    // `mavenLocal()` is here for one coordinate: `at.aimon.core:aimon-memory-testkit`, which the contract
    // source set in `aimon-memory-client` subclasses and which is on no remote repository until aimon-core
    // 0.3.0 ships. Nothing on the path from `git clone` to `checkAll` reaches through it — aimon-core itself
    // is pinned to a released 0.2.4 (gradle/libs.versions.toml) — so a machine that has never published
    // aimon-core locally builds and passes every gate, and only the contract tier skips itself.
    //
    // Ordered after Central so a released artifact always wins over whatever a `publishToMavenLocal` left
    // behind. Remove it when 0.3.0 puts the testkit on Central and `aimonTestkit` folds back into `aimonCore`.
    //
    // Note these are the fallback and not the repositories in force: the root build.gradle.kts declares them
    // per project, and Gradle's default `PREFER_PROJECT` mode lets those win — it says so in the resolution
    // failure, which is a strange place to learn it. Both blocks are kept saying the same thing so the answer
    // does not depend on which one a reader happens to find.
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

include(
    // The bill of materials. A `java-platform`, so it is the one subproject that cannot apply
    // `aimon.java-conventions` (`java-platform` and `java-library` are mutually exclusive) — the root
    // aggregators skip platforms for that reason.
    "aimon-memory-bom",
    "aimon-memory-core",
    // The shared fixture and stub support. Not published: it exists so the modules under test share one
    // description of a golden file and one stub embedder, not so anyone downstream depends on it.
    "aimon-memory-testkit",
    "aimon-memory-text",
    "aimon-memory-embed",
    "aimon-memory-llm",
    "aimon-memory-store",
    "aimon-memory-recall",
    // The five tiers themselves — derive, dialectic, dream, fan-out, ingest, recall context. Named
    // `engine` rather than `memory` so the module and its package do not repeat the product name.
    "aimon-memory-engine",
    "aimon-memory-worker",
    "aimon-memory-api",
    // The adapter aimon-core consumes: `at.aimon.core.memory.PeerMemory` implemented over this service's
    // HTTP API. Compiled to Java 17 because that is aimon-core's floor; nothing else here is.
    "aimon-memory-client",
)

project(":aimon-memory-bom").projectDir = file("modules/aimon-memory-bom")
project(":aimon-memory-core").projectDir = file("modules/aimon-memory-core")
project(":aimon-memory-testkit").projectDir = file("modules/aimon-memory-testkit")
project(":aimon-memory-text").projectDir = file("modules/aimon-memory-text")
project(":aimon-memory-embed").projectDir = file("modules/aimon-memory-embed")
project(":aimon-memory-llm").projectDir = file("modules/aimon-memory-llm")
project(":aimon-memory-store").projectDir = file("modules/aimon-memory-store")
project(":aimon-memory-recall").projectDir = file("modules/aimon-memory-recall")
project(":aimon-memory-engine").projectDir = file("modules/aimon-memory-engine")
project(":aimon-memory-worker").projectDir = file("modules/aimon-memory-worker")
project(":aimon-memory-api").projectDir = file("modules/aimon-memory-api")
project(":aimon-memory-client").projectDir = file("modules/aimon-memory-client")
