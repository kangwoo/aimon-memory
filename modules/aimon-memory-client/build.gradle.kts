// The adapter aimon-core consumes: `at.aimon.core.memory.PeerMemory` over this service's HTTP API.
//
// It depends on aimon-core and on nothing else in this build. That is deliberate — an application that wants remote
// memory should not end up with pgvector, Flyway, Lucene and a Spring Boot application on its classpath, and nothing
// in the five tiers needs them.
plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

// Java 17 bytecode, because that is aimon-core's floor and this is the one module it compiles against.
// `--release` rather than a second toolchain: it pins the *API* as well as the class file version, so a Java 21
// method cannot slip in and fail at the consumer instead of here, and it needs no extra JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(libs.findVersion("javaCompat").orElseThrow().requiredVersion.toInt())
}

dependencies {
    // `api`, not `implementation`: every tier this module implements is an aimon-core type, so the contract is on
    // the consumer's compile classpath by definition.
    api(libs.findLibrary("aimon-core").get())
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(libs.findLibrary("assertj").get())
}

// ── the contract tier ────────────────────────────────────────────────────────────────────────────────────────
//
// aimon-core's five-tier `PeerMemory` contract suite, in a source set of its own rather than in `src/test`.
//
// The suite arrives as `at.aimon.core:aimon-memory-testkit`. That artifact is on no remote repository — it first
// ships in aimon-core 0.3.0, and the only copy anywhere is whatever `publishToMavenLocal` left in a ~/.m2. A test
// that subclasses it cannot be skipped at runtime the way a missing Docker daemon is: an unresolvable superclass
// fails `compileTestJava`, which took this module, and with it `checkAll`, down on every machine that had never
// published aimon-core locally. That is every fresh clone and every CI runner — the state this repository was in
// before this block existed, and it is not a state an open repository can be published in.
//
// So the coordinate is confined here, the classpath is resolved leniently, and the tier's tasks skip themselves
// when the testkit is not among the artifacts that came back. On a machine that has it nothing changes:
// `contractTest` compiles and runs the twenty-one cases, and the root `checkAll` names the task. On a machine
// that does not, the tier says so by name and the rest of the build is untouched.
//
// Skipping quietly is the risk that buys, and two guards below hold it down, because this is the one gate CI
// cannot check — a runner never has the testkit, so it always skips and would never report the tier going dark.
// `verifyContractTestClasspath` narrows the leniency to the testkit, so nothing else that fails to resolve is
// swallowed with it; `verifyContractTestRan` fails when the tier was allowed to run and produced no cases. Both
// were written against demonstrated failures rather than imagined ones — see their own comments.
//
// The dependency is reached by GAV and never as `project(":aimon-memory-testkit")`. This build has an internal
// module of that exact name — fixtures for the five service tiers, unrelated — and the project notation would
// resolve to it without failing, which is the quiet version of a green build measuring the wrong thing.
//
// Fold this back into `src/test` when 0.3.0 is on Central: the reason for the split is the artifact's
// availability, and nothing else.

val testkitCoordinate = "at.aimon.core:aimon-memory-testkit"
val testkitVersion = libs.findVersion("aimonTestkit").orElseThrow().requiredVersion

val javaSourceSets = the<SourceSetContainer>()
val mainSourceSet = javaSourceSets["main"]
val contractSourceSet = javaSourceSets.create("contractTest")

// The same dependencies `src/test` has, plus the suite. Extending rather than restating them keeps the two test
// source sets from drifting into different notions of what a test may use.
configurations.getByName(contractSourceSet.implementationConfigurationName)
    .extendsFrom(configurations.getByName("testImplementation"))
configurations.getByName(contractSourceSet.runtimeOnlyConfigurationName)
    .extendsFrom(configurations.getByName("testRuntimeOnly"))

dependencies {
    contractSourceSet.implementationConfigurationName(libs.findLibrary("aimon-testkit").get())
}

// Lenient, which is the whole mechanism: an artifact view that tolerates failures hands back what did resolve
// instead of throwing, so "the testkit is absent" becomes a question the tasks below can ask rather than an error
// that has already happened by the time they are consulted.
fun lenientFiles(configurationName: String): FileCollection =
    configurations.getByName(configurationName).incoming.artifactView { isLenient = true }.files

/**
 * Fails when anything on this classpath failed to resolve except the testkit.
 *
 * Leniency is a licence to ignore one known-absent artifact, and it was written as a licence to ignore all of
 * them. `isLenient = true` swallows every resolution failure on the configuration, and this classpath extends
 * `testImplementation` — so assertj, Jackson, the JUnit API and the platform launcher are all on it. A typo in a
 * version bump, a repository outage or a missing mirror entry would have been swallowed exactly like the
 * testkit's absence: the compile can pass anyway when the sources do not name the missing symbol, the JUnit
 * engine then finds nothing to run, and the tier reports success having measured nothing.
 *
 * That was demonstrated rather than imagined — an unresolvable coordinate added to this source set produced
 * BUILD SUCCESSFUL, where the same coordinate on `src/test` failed the build in three seconds. This is the one
 * gate CI cannot check, because a runner never has the testkit and always skips; nothing outside the build would
 * have noticed it going quiet.
 *
 * So the leniency is narrowed here to the coordinate it was granted for. Anything else that did not resolve is
 * an ordinary broken build and is reported as one.
 */
fun requireNothingButTestkitUnresolved(configurationName: String) {
    val foreign = configurations.getByName(configurationName).incoming.resolutionResult.allDependencies
        .filterIsInstance<UnresolvedDependencyResult>()
        .map { it.attempted.displayName }
        .filterNot { it.startsWith(testkitCoordinate) }
        .distinct()
    if (foreign.isNotEmpty()) {
        throw GradleException(
            "the contract tier's $configurationName has unresolved dependencies that are not the testkit: " +
                foreign.joinToString(", ") + ". This classpath is resolved leniently so that a missing " +
                "$testkitCoordinate skips the tier instead of failing the build; leniency is not a licence to " +
                "ignore anything else. Left unchecked, a missing JUnit engine here would discover no tests and " +
                "the tier would report success having run nothing.",
        )
    }
}

/** Whether the contract suite can run at all, which is to say whether the testkit resolved. */
fun testkitResolved(): Boolean =
    configurations.getByName(contractSourceSet.compileClasspathConfigurationName)
        .incoming.artifactView { isLenient = true }.artifacts
        .any { it.id.componentIdentifier.displayName.startsWith(testkitCoordinate) }

/**
 * The check above, as a task the tier's own tasks depend on.
 *
 * A task rather than a clause inside their `onlyIf`, which is where this started. A predicate that throws does
 * fail the build, but Gradle reports it as "Could not evaluate spec for 'Task satisfies onlyIf spec'" and drops
 * the message — so the build broke without saying which coordinate was missing, which is most of what a reader
 * needs. Here the message is the task's own failure and is printed.
 *
 * Being a dependency also means it runs even when the tier goes on to skip itself: a machine without the testkit
 * still gets told if something else on that classpath is broken, rather than reading the skip as the whole story.
 *
 * Both classpaths, not just the compile one. The platform launcher and the Jupiter engine reach this source set
 * through `testRuntimeOnly`, so a runtime-only coordinate going missing is invisible to a compile-classpath check
 * and is exactly how this tier would end up discovering nothing to run.
 */
val verifyContractTestClasspath by tasks.registering {
    description = "Fails if anything other than the testkit failed to resolve for the contract tier."
    group = "verification"
    outputs.upToDateWhen { false }
    doLast {
        requireNothingButTestkitUnresolved(contractSourceSet.compileClasspathConfigurationName)
        requireNothingButTestkitUnresolved(contractSourceSet.runtimeClasspathConfigurationName)
    }
}

contractSourceSet.compileClasspath =
    lenientFiles(contractSourceSet.compileClasspathConfigurationName) + mainSourceSet.output
contractSourceSet.runtimeClasspath =
    contractSourceSet.output + lenientFiles(contractSourceSet.runtimeClasspathConfigurationName) +
    mainSourceSet.output

tasks.named<JavaCompile>("compileContractTestJava") {
    dependsOn(verifyContractTestClasspath)
    // Logged from inside the predicate rather than a `doFirst`, which a skipped task never reaches. Gradle's own
    // "Skipping task ... as task onlyIf is false" names the task and not the reason, and the reason here is a
    // fact about a machine — an absent artifact — that a reader has no other way to discover.
    onlyIf {
        val resolved = testkitResolved()
        if (!resolved) {
            logger.lifecycle(
                "aimon-memory-client: skipping the PeerMemory contract tier. $testkitCoordinate did not " +
                    "resolve, and it is on no remote repository until aimon-core 0.3.0 ships. To run it, " +
                    "publish that from an aimon-core checkout with " +
                    "`./gradlew publishToMavenLocal -PVERSION_NAME=$testkitVersion`.",
            )
        }
        resolved
    }
}

// Test sources are exempt from Checkstyle throughout this build — aimon.java-conventions disables
// `checkstyleTest` — and a second test source set is not a different case.
tasks.named<Checkstyle>("checkstyleContractTest") {
    enabled = false
}

val contractTest by tasks.registering(Test::class) {
    description = "Runs aimon-core's five-tier PeerMemory contract suite against RemotePeerMemory."
    group = "verification"
    testClassesDirs = contractSourceSet.output.classesDirs
    classpath = contractSourceSet.runtimeClasspath
    // JUnit Platform, the heap floor and the replay-only LLM mode all arrive from aimon.java-conventions, which
    // configures every Test task in the build rather than the one named `test`.
    shouldRunAfter(tasks.named("test"))
    dependsOn(verifyContractTestClasspath)
    onlyIf { testkitResolved() }
}

/**
 * Fails when the tier was supposed to run and produced no cases.
 *
 * A `Test` task with no filter and nothing to run succeeds. That is the right answer almost everywhere and the
 * wrong one here, because this tier already has a legitimate way of contributing nothing — the skip above — and a
 * second, silent one is indistinguishable from it in a build log. A renamed class, a package move, a typo in the
 * source set directory or an engine that failed to resolve would each take the twenty-one cases to zero and leave
 * the gate green. No CI run would catch it: CI never gets this far. `onlyIf` having let the task through means
 * the testkit resolved, so running nothing is a defect by definition.
 *
 * `Test.failOnNoDiscoveredTests` says this in one line and arrives in Gradle 8.13; this build is on 8.10.
 *
 * A finalizer reading the results off disk, rather than a counter and a `doLast` inside the task, which is where
 * this started and which does not work. Delete every class from the source set and the `Test` task has no
 * candidates, so Gradle skips it as NO-SOURCE and its own actions never run — the exact case worth catching is
 * the one a `doLast` cannot see. A finalizer runs whether the task it follows executed, skipped or was
 * up-to-date. Counting files also survives an up-to-date run, where an in-process counter would read zero and
 * fail a build that is perfectly fine.
 */
val verifyContractTestRan by tasks.registering {
    description = "Fails if the contract tier resolved the testkit and then ran no cases."
    group = "verification"
    outputs.upToDateWhen { false }
    onlyIf { testkitResolved() }

    val results = contractTest.flatMap { it.reports.junitXml.outputLocation }
    doLast {
        val written = results.get().asFile.listFiles().orEmpty()
            .filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") }
        if (written.isEmpty()) {
            throw GradleException(
                "the contract tier resolved $testkitCoordinate and then ran no cases — no results under " +
                    results.get().asFile + ". The suite is reached by subclassing " +
                    "AbstractPeerMemoryContractTest from src/contractTest/java, so nothing here means that " +
                    "subclass is gone, renamed, outside that source set, or without an engine to run it. It does " +
                    "not mean there was nothing to check.",
            )
        }
    }
}

contractTest.configure { finalizedBy(verifyContractTestRan) }

/**
 * Refuses to publish against an aimon-core that does not contain the API this module implements.
 *
 * This module was written before any release had `PeerMemory`: at.aimon.core:aimon-core:0.2.3 carried the older
 * ObservationStore generation, so the build compiled against a sibling checkout through a composite build, and
 * publishing then would have shipped a POM pointing at an artifact no consumer could load. 0.2.4 has the five tiers,
 * the composite is gone, and this check now passes on its own.
 *
 * It stays because the failure it names is not a thing that happened once. Both ways back into it are one line: an
 * `includeBuild` in settings.gradle.kts, which resolves the coordinate to a *project* and makes a green build a
 * measurement of a working tree nobody can download; or a downgrade of `aimonCore` below 0.2.4. The check asks the
 * resolution result which of the two answered, and opens the jar to be sure the class is actually in it.
 *
 * Deliberately not a detached configuration resolving the coordinate by hand. That was the first attempt and it
 * reported success even against 0.2.3: composite substitution applies to detached configurations too, so the check
 * downloaded nothing and inspected the same local checkout it was written to catch.
 *
 * The `-SNAPSHOT` clause was added after a third way in was walked through, not imagined. Wiring the contract suite
 * needed `at.aimon.core:aimon-memory-testkit`, which is not on Central until aimon-core 0.3.0, so the way forward
 * was `publishToMavenLocal -PVERSION_NAME=0.3.0-SNAPSHOT` plus `mavenLocal()` and a snapshot pin in the catalog.
 * That arrives past both existing clauses: a snapshot in ~/.m2 IS a `ModuleComponentIdentifier`, and its jar does
 * contain `PeerMemory`. The check said "released artifact" about a jar that exists on one laptop — the very POM the
 * comment above calls out, "pointing at an aimon-core nobody else has", reached through a different door. Versions,
 * not just identifier types, are what "released" means.
 *
 * That pin is gone: it made this repository unbuildable for everyone who had not published aimon-core locally,
 * which is a worse problem than the one it solved. The clause stays. It was written about a door somebody walked
 * through once, and the version catalog still holds an unreleased coordinate — `aimonTestkit` — a few lines from
 * the released one.
 */
val verifyCoreIsReleased by tasks.registering {
    description = "Fails if the aimon-core this module is built against is a local checkout or lacks PeerMemory."
    group = "verification"
    outputs.upToDateWhen { false }

    val classpath = configurations.named("compileClasspath")

    doLast {
        val resolution = classpath.get().incoming.resolutionResult
        val core = resolution.allComponents.firstOrNull { it.moduleVersion?.name == "aimon-core" }
            ?: throw GradleException("aimon-core is not on this module's compile classpath at all")

        val id = core.id
        if (id !is ModuleComponentIdentifier) {
            throw GradleException(
                "aimon-core resolved to $id — a project rather than a released artifact, which means something has " +
                    "put an `includeBuild` back in settings.gradle.kts. Publishing aimon-memory-client from that " +
                    "build would ship a POM pointing at an aimon-core nobody else has.",
            )
        }

        if (id.version.endsWith("-SNAPSHOT")) {
            throw GradleException(
                "aimon-core resolved to $id — a snapshot, which is a local or transient build rather than a " +
                    "released one. Publishing aimon-memory-client against it would ship a POM pointing at an " +
                    "aimon-core nobody else can resolve, the same failure an `includeBuild` causes. This is the " +
                    "state `publishToMavenLocal -PVERSION_NAME=<x>-SNAPSHOT` plus `mavenLocal()` puts the build " +
                    "in: fine to build and test against, never to publish from. Move `aimonCore` in " +
                    "gradle/libs.versions.toml back to a released version. Note that reaching the contract suite " +
                    "is not a reason to move it — the suite has its own `aimonTestkit` version and its own source " +
                    "set, precisely so an unreleased artifact cannot end up on this module's compile classpath.",
            )
        }

        val jar = classpath.get().resolvedConfiguration.resolvedArtifacts
            .firstOrNull { it.moduleVersion.id.name == "aimon-core" }?.file
            ?: throw GradleException("aimon-core resolved to $id but produced no artifact to inspect")
        val hasPeerMemory = zipTree(jar).matching { include("at/aimon/core/memory/PeerMemory.class") }
            .files.isNotEmpty()
        if (!hasPeerMemory) {
            throw GradleException(
                "$id does not contain at.aimon.core.memory.PeerMemory, so publishing aimon-memory-client against " +
                    "it would ship an artifact no consumer can load. 0.2.4 is the first release that has it.",
            )
        }
        logger.lifecycle("$id is a released artifact and contains PeerMemory")
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyCoreIsReleased)
}
