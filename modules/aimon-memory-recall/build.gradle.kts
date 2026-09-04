plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    api(project(":aimon-memory-core"))
    api(project(":aimon-memory-store"))
    implementation(project(":aimon-memory-text"))
    implementation("org.springframework:spring-context")

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(project(":aimon-memory-text"))
}
