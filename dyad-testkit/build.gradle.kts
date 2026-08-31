val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

// A main-source-set module rather than a test fixture, because several modules' tests consume it and
// cross-project test fixtures in Gradle are more ceremony than this is worth.
dependencies {
    api(project(":dyad-core"))
    // The stub embedder tokenizes with the same analyzer the runtime uses, so a Korean fixture
    // behaves the way Korean actually behaves rather than the way a whitespace split pretends it does.
    api(project(":dyad-text"))
    api("com.fasterxml.jackson.core:jackson-databind")
    api(libs.findLibrary("tc-postgres").get())
    api(libs.findLibrary("tc-junit").get())
    api(libs.findLibrary("assertj").get())
    api("org.flywaydb:flyway-core")
    api("org.springframework:spring-jdbc")
    // A real pool. Without one every statement opens a TCP connection and authenticates, which makes
    // the load profile measure the harness rather than the system, and slows the whole suite.
    api("com.zaxxer:HikariCP")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    api("org.postgresql:postgresql")
    implementation("org.junit.jupiter:junit-jupiter-api")
}
