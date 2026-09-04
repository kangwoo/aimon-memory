plugins {
    id("aimon.java-conventions")
    id("org.springframework.boot")
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")


dependencies {
    implementation(project(":aimon-memory-core"))
    implementation(project(":aimon-memory-recall"))
    implementation(project(":aimon-memory-engine"))
    implementation(project(":aimon-memory-store"))
    implementation(project(":aimon-memory-text"))
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

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(libs.findLibrary("archunit").get())
}
