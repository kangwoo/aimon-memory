val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":dyad-core"))
    implementation(project(":dyad-recall"))
    implementation(project(":dyad-memory"))
    implementation(project(":dyad-store"))
    implementation(project(":dyad-text"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation(libs.findLibrary("jjwt-api").get())
    runtimeOnly(libs.findLibrary("jjwt-impl").get())
    runtimeOnly(libs.findLibrary("jjwt-jackson").get())
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation(project(":dyad-testkit"))
    testImplementation(libs.findLibrary("archunit").get())
}
