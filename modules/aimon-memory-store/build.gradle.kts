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
    // `implementation`, not `api`. This was `api` because `Jsonb.of` returned a `PGobject`, which put
    // the driver on the compile classpath of recall, engine, api and worker — none of which name a
    // postgresql type, and none of which should be able to. That is the leak
    // `ModuleDependencyTest.jdbcIsConfinedToThePersistenceModule` was written to catch after the fact;
    // with the driver off their compile classpath the accidental import does not compile in the first
    // place, and the rule becomes a second line rather than the only one.
    //
    // Still a full `implementation` rather than `runtimeOnly`: one file here compiles against the
    // driver — `Jsonb` builds the `PGobject` that carries a `jsonb` parameter — it just no longer
    // returns one. Consumers keep it at runtime, which is what a POM's `runtime` scope is for and
    // what `aimon-memory-store` published on its own needs to work.
    implementation("org.postgresql:postgresql")

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(project(":aimon-memory-text"))
}
