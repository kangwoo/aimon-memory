plugins {
    id("aimon.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":aimon-memory-core"))
    implementation(project(":aimon-memory-engine"))
    implementation(project(":aimon-memory-store"))
    // `llm`, `embed` and `text` used to be declared here and are not any more: this module's main
    // sources import nothing from any of the three. The provider backends and the embedder are
    // assembled by `MemoryConfiguration`, which lives in `engine` and declares them itself, so they
    // reach the bootJar over `engine`'s runtime classpath either way — which is why `aimon-memory-api`
    // has never named them and still runs the whole stack.

    // starter-web, not the plain starter: without a servlet container the actuator endpoints have
    // nothing to serve on, and the worker's metrics — queue depth, queue age, work unit duration —
    // are the ones an operator most needs to see.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation("org.flywaydb:flyway-core")

    testImplementation(project(":aimon-memory-testkit"))
    // Both are test-only here, and both were being obtained by accident. `text` came in on the
    // `implementation` line removed above, which the main sources did not need; `recall` arrives
    // through `engine`'s `api(recall)`, so `LoadTest` compiles against a module this build never said
    // it used. Declared where they are actually needed, the way `store` and `recall` already declare
    // `text` for their own tests.
    testImplementation(project(":aimon-memory-text"))
    testImplementation(project(":aimon-memory-recall"))
}

// Deliberately separate from `check`: it is a measurement, not a gate, and it takes minutes.
tasks.register<Test>("loadTest") {
    description = "Concurrent ingestion and recall, with a latency profile."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "2g"
    systemProperty("aimon.memory.load", "true")
    listOf("pairs", "perPair", "readers", "reads", "writers").forEach { knob ->
        System.getProperty("aimon.memory.load.$knob")?.let { systemProperty("aimon.memory.load.$knob", it) }
    }
    filter { includeTestsMatching("at.aimon.memory.worker.LoadTest") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
