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
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(project(":aimon-memory-llm"))
}
