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
    // No `aimon-memory-text` here. It was declared and never used — not one `at.aimon.memory.text`
    // import in this module's main or test sources — and `store` exposes it with `api()` anyway, so
    // the line bought nothing and made the declared graph disagree with the real one. That
    // disagreement is what §5 of docs/architecture.md keeps getting wrong; the fix is for the build
    // files to be worth reading.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation(libs.findLibrary("springdoc").get())
    implementation(libs.findLibrary("jjwt-api").get())
    runtimeOnly(libs.findLibrary("jjwt-impl").get())
    runtimeOnly(libs.findLibrary("jjwt-jackson").get())
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation(project(":aimon-memory-testkit"))
    testImplementation(libs.findLibrary("archunit").get())
}

// ── the architecture gate's subjects ─────────────────────────────────────────────────────────────
//
// `ModuleDependencyTest` reads every module's compiled output off disk rather than from this module's
// classpath, so one ArchUnit run covers the whole system instead of the part the API happens to
// depend on. Gradle could not see that: those directories were neither an input of `test` nor
// produced by anything it depended on, and two things followed from the same omission.
//
// `./gradlew :aimon-memory-api:test` on a fresh clone failed. `:aimon-memory-worker` is on no path
// from here — `--dry-run` showed eight sibling `compileJava` tasks and not that one — so its classes
// were never built and `everyModuleWasActuallyImported` failed on a module nothing was wrong with.
//
// The quieter one matters more. With `org.gradle.parallel=true` this task could run before a sibling
// recompiled, or stay UP-TO-DATE across a change to it, and check bytecode from an earlier build. The
// rules that only exist here are the ones the compiler cannot state — `jdbcIsConfinedToThePersistence
// Module`, `productionCodeNeverDependsOnTheTestkit` — so a gate that cannot say when it last looked
// is most of the enforcement this repository claims in docs/architecture.md §5.
//
// `dependsOn` orders the compilation before the check; `inputs.files` makes a change in any subject
// re-run it. The path is spelled the way the test spells it, because it is the same claim.
//
// Keep this list equal to the one in ModuleDependencyTest. It is not load-bearing in both directions:
// a module added there and missing here fails loudly on a fresh clone, which is the direction that
// gets noticed. A module added here and missing there costs an ordering edge and nothing else.
val architectureSubjects = listOf(
    "core", "testkit", "text", "embed", "llm", "store", "recall", "engine", "worker", "api",
)

tasks.named<Test>("test") {
    architectureSubjects.forEach { dependsOn(":aimon-memory-$it:classes") }
    inputs.files(
        architectureSubjects.map {
            rootProject.layout.projectDirectory.dir("modules/aimon-memory-$it/build/classes/java/main")
        },
    ).withPropertyName("architectureSubjects").withPathSensitivity(PathSensitivity.RELATIVE)
}
