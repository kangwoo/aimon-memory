plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    api(project(":aimon-memory-core"))
    api(project(":aimon-memory-text"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework:spring-context")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    api("org.postgresql:postgresql")

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(project(":aimon-memory-text"))
}
