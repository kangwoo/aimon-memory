plugins {
    id("aimon.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":aimon-memory-core"))
    implementation(project(":aimon-memory-engine"))
    implementation(project(":aimon-memory-store"))
    implementation(project(":aimon-memory-llm"))
    implementation(project(":aimon-memory-embed"))
    implementation(project(":aimon-memory-text"))
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
