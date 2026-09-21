// Depends on aimon-memory-recall because Tier 2 exposes Tier 1 as a tool. That is the point of the design:
// giving the agentic loop a good retriever is what lowers its iteration count.

plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    api(project(":aimon-memory-core"))
    api(project(":aimon-memory-store"))
    api(project(":aimon-memory-recall"))
    implementation(project(":aimon-memory-llm"))
    implementation(project(":aimon-memory-embed"))
    implementation("org.springframework.boot:spring-boot")
    implementation(project(":aimon-memory-text"))
    implementation("tools.jackson.core:jackson-databind")
    // Declared although jackson-databind already brings it: `DerivedConclusions` and `DreamOutput`
    // import `com.fasterxml.jackson.annotation.JsonIgnoreProperties`, and Jackson 3 deliberately left
    // the annotations at their Jackson 2 coordinate. Before the Jackson 3 move the declared and the
    // imported artifact were the same one; now they are different products, and leaving it transitive
    // is the kind of disagreement between the declared graph and the real one that the comment in
    // `aimon-memory-api/build.gradle.kts` calls a defect.
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(project(":aimon-memory-llm"))
}
