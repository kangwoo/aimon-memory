rootProject.name = "aimon-memory"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// aimon-memory-client implements `at.aimon.core.memory.PeerMemory`, and no released aimon-core contains it:
// at.aimon.core:aimon-core:0.2.3 on Central is from before that API landed, and carries the older
// ObservationStore/PeerView generation instead. So the client is built against a sibling checkout when there
// is one, and against the catalog coordinate when there is not.
//
// Deliberately loud, and deliberately not a fallback that silently changes what the code compiles against:
// a composite build substitutes the whole module, so the difference between "checked against your working
// aimon-core" and "checked against a release" is the difference between a green build that means something
// and one that does not. Point it somewhere else with -PaimonCoreDir=/path/to/aimon-core, or turn it off
// with -PaimonCoreDir=none.
val aimonCoreDir: String? = (settings as ExtensionAware).extra.properties["aimonCoreDir"] as String?
    ?: startParameter.projectProperties["aimonCoreDir"]
val aimonCore: File? = when {
    aimonCoreDir == "none" -> null
    aimonCoreDir != null -> file(aimonCoreDir)
    else -> file("../aimon-core").takeIf { it.resolve("settings.gradle.kts").isFile }
}
if (aimonCore != null) {
    require(aimonCore.resolve("settings.gradle.kts").isFile) {
        "aimonCoreDir=$aimonCore is not an aimon-core checkout (no settings.gradle.kts)"
    }
    // Substitution is spelled out rather than left to Gradle's matching. aimon-core's root project and its
    // `:aimon-core` subproject both answer to at.aimon.core:aimon-core, and the composite refuses an ambiguous
    // coordinate: "not unique in composite: can be provided by [project :aimon-core, project :aimon-core:aimon-core]".
    includeBuild(aimonCore) {
        dependencySubstitution {
            substitute(module("at.aimon.core:aimon-core")).using(project(":aimon-core"))
            substitute(module("at.aimon.core:aimon-memory-testkit")).using(project(":aimon-memory-testkit"))
        }
    }
    println("aimon-memory-client: building against the aimon-core checkout at ${aimonCore.canonicalPath}")
} else {
    println(
        "aimon-memory-client is NOT in this build: it implements at.aimon.core.memory.PeerMemory, which no released "
            + "aimon-core contains yet. Check aimon-core out beside this repository, or pass "
            + "-PaimonCoreDir=/path/to/aimon-core.",
    )
}

// The adapter aimon-core consumes: `at.aimon.core.memory.PeerMemory` implemented over this service's HTTP API.
// Compiled to Java 17 because that is aimon-core's floor; nothing else here is.
//
// Included only when there is an aimon-core to compile it against. Leaving it in unconditionally would mean a
// contributor with no sibling checkout cannot build the service either, for a module they are not touching — and
// the failure would be a compile error about missing symbols rather than a sentence saying what is absent.
if (aimonCore != null) {
    include("aimon-memory-client")
    project(":aimon-memory-client").projectDir = file("modules/aimon-memory-client")
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
