dependencies {
    api(project(":dyad-core"))
    api(project(":dyad-text"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework:spring-context")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    api("org.postgresql:postgresql")

    testImplementation(project(":dyad-testkit"))
    testImplementation(project(":dyad-text"))
}
