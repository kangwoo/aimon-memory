plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    api(project(":aimon-memory-core"))
    api(project(":aimon-memory-store"))
    implementation(project(":aimon-memory-text"))
    // `RecallConfiguration` imports `EmbedConfiguration`, so that Tier 1 can be assembled from this
    // module and the ones below it. The dependency is on the wiring, not on an embedder: everything
    // here still talks to `core.spi.Embedder` and nothing in this module names an implementation.
    // docs/architecture.md §5 listed this edge for a long time before it existed.
    implementation(project(":aimon-memory-embed"))
    implementation("org.springframework:spring-context")

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(project(":aimon-memory-text"))
}
