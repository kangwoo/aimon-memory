// Depends on dyad-recall because Tier 2 exposes Tier 1 as a tool. That is the point of the design:
// giving the agentic loop a good retriever is what lowers its iteration count.
dependencies {
    api(project(":dyad-core"))
    api(project(":dyad-store"))
    api(project(":dyad-recall"))
    implementation(project(":dyad-llm"))
    implementation(project(":dyad-embed"))
    implementation("org.springframework.boot:spring-boot")
    implementation(project(":dyad-text"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")

    testImplementation(project(":dyad-testkit"))
    testImplementation(project(":dyad-llm"))
}
