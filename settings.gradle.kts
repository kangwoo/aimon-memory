rootProject.name = "dyad"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(
    "dyad-core",
    "dyad-testkit",
    "dyad-text",
    "dyad-embed",
    "dyad-llm",
    "dyad-store",
    "dyad-recall",
    "dyad-memory",
    "dyad-worker",
    "dyad-api",
)
