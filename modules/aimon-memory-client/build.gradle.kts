// The adapter aimon-core consumes: `at.aimon.core.memory.PeerMemory` over this service's HTTP API.
//
// It depends on aimon-core and on nothing else in this build. That is deliberate — an application that
// wants remote memory should not end up with pgvector, Flyway, Lucene and a Spring Boot application on
// its classpath, and nothing in the five tiers needs them.
plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

// Java 17 bytecode, because that is aimon-core's floor and this is the one module it compiles against.
// `--release` rather than a second toolchain: it pins the *API* as well as the class file version, so a
// Java 21 method cannot slip in and fail at the consumer instead of here, and it needs no extra JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(libs.findVersion("javaCompat").orElseThrow().requiredVersion.toInt())
}

dependencies {
    // `api`: every tier this module implements is an aimon-core type, so the contract is on the
    // consumer's compile classpath by definition.
    api(libs.findLibrary("aimon-core").get())
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
