import javax.xml.parsers.DocumentBuilderFactory

import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.w3c.dom.Element

// One coordinate an application imports to get every published AIMON Memory module at the same version,
// instead of repeating that version on each dependency line.
//
// A `java-platform`, not a library, so it cannot apply `aimon.java-conventions` — `java-platform` and
// `java-library` are mutually exclusive in Gradle. That is why the root aggregators skip platform
// projects, and why the verification below is a task rather than a JUnit test.
plugins {
    `java-platform`
    id("aimon.publishable")
}

// Only this project's own modules. Pinning third-party versions here would look helpful and behave badly:
// Gradle treats a `platform()` version as a recommendation, but Maven's `dependencyManagement` and
// `enforcedPlatform` treat it as an override, so a Maven application importing this BOM would have its
// Spring Boot-managed versions silently replaced by whatever this repository happened to build against.
//
// The list is derived, never typed — a hand-maintained BOM is a BOM that is one release behind.
dependencies {
    constraints {
        publishedProjects().forEach { api(it) }
    }
}

/**
 * Every sibling that actually publishes, in a stable order.
 *
 * `evaluationDependsOn` is what makes this honest rather than accidental. Gradle configures projects in
 * path order, so when this script runs most siblings have not applied their plugins yet and `hasPlugin`
 * would answer "no" for everything sorting after this project. Forcing each sibling to be evaluated first
 * is the supported way to ask a question about its configuration.
 */
fun publishedProjects(): List<Project> =
    rootProject.subprojects
        .filter { it.path != project.path }
        .map { evaluationDependsOn(it.path) }
        .filter { it.plugins.hasPlugin("com.vanniktech.maven.publish") }
        .sortedBy { it.name }

/**
 * Modules that declare a published coordinate, according to their own `gradle.properties`.
 *
 * Deliberately a different signal from the one the constraints are built from. A BOM that checked itself
 * against the list it derived itself from would only ever confirm that a list equals itself. Reading the
 * declared coordinates instead catches the two ways a module can be half-published: applying the plugin
 * without declaring a coordinate (Central rejects a POM with no name and no description), or declaring one
 * and never applying the plugin (a coordinate that looks real in the tree and is never produced).
 */
fun declaredCoordinates(): Map<String, Map<String, String>> =
    rootProject.subprojects
        .filter { it.path != project.path }
        .mapNotNull { module ->
            val properties = module.file("gradle.properties")
            if (!properties.exists()) return@mapNotNull null
            val declared = properties.readLines()
                .map { it.trim() }
                .filter { it.startsWith("POM_") && it.contains("=") }
                .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
            if (declared.containsKey("POM_ARTIFACT_ID")) module.name to declared else null
        }
        .toMap()

val requiredPomProperties = listOf("POM_ARTIFACT_ID", "POM_NAME", "POM_DESCRIPTION")

val verifyBom by tasks.registering {
    description = "Checks that the BOM manages exactly the modules this build publishes, and nothing else."
    group = "verification"
    dependsOn(tasks.named("generatePomFileForMavenPublication"))

    val pomFile = layout.buildDirectory.file("publications/maven/pom-default.xml")
    val published = publishedProjects().map { it.name }.toSet()
    val declared = declaredCoordinates()
    val expectedGroup = project.group.toString()
    val expectedVersion = project.version.toString()

    inputs.file(pomFile)
    outputs.upToDateWhen { false }

    doLast {
        val problems = mutableListOf<String>()

        (published - declared.keys).sorted().forEach {
            problems += "module '$it' applies aimon.publishable but declares no POM_ARTIFACT_ID in its " +
                "gradle.properties — its POM would reach Central with no name and no description"
        }
        (declared.keys - published).sorted().forEach {
            problems += "module '$it' declares a published coordinate but does not apply " +
                "aimon.publishable, so that coordinate is never produced"
        }
        declared.filterKeys { it in published }.forEach { (module, properties) ->
            requiredPomProperties.filterNot { properties.containsKey(it) }
                .forEach { problems += "module '$module' is published but declares no $it" }
        }

        // Only compared when the coordinates already agree: a module missing from both sides would
        // otherwise be reported as "this build publishes ⟨list without it⟩", which is not true and points
        // away from the one-line fix the checks above already named.
        val coordinatesDisagree = problems.isNotEmpty()

        val pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.get().asFile)
        val root = pom.documentElement

        if (childElement(root, "dependencies") != null) {
            problems += "the BOM's POM has a <dependencies> block; a platform must only manage versions, " +
                "never put anything on a consumer's classpath"
        }

        val managed = childElement(childElement(root, "dependencyManagement"), "dependencies")
            ?.let { elements(it, "dependency") }
            .orEmpty()
            .map { Triple(text(it, "groupId"), text(it, "artifactId"), text(it, "version")) }

        val expected = published.mapNotNull { declared[it]?.get("POM_ARTIFACT_ID") }.toSortedSet()
        val actual = managed.map { it.second }.toSortedSet()
        if (!coordinatesDisagree && actual != expected) {
            problems += "the BOM manages $actual but this build publishes $expected"
        }
        managed.filterNot { it.first == expectedGroup && it.third == expectedVersion }.forEach {
            problems += "managed entry '${it.first}:${it.second}:${it.third}' is not " +
                "$expectedGroup:*:$expectedVersion — the BOM is not in lockstep with the release"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString(
                    prefix = "The BOM does not match what this build publishes:\n  - ",
                    separator = "\n  - ",
                ),
            )
        }
        logger.lifecycle("aimon-memory-bom manages ${managed.size} modules at $expectedGroup:*:$expectedVersion")
    }
}

// A wrong BOM is only discoverable after it is permanent, so the check rides the publish itself rather
// than relying on the aggregate gate having been run in the same invocation.
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyBom)
}

fun childElement(parent: Element?, name: String): Element? =
    parent?.childNodes?.let { nodes ->
        (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
            .firstOrNull { it.tagName == name }
    }

fun elements(parent: Element, name: String): List<Element> =
    parent.childNodes.let { nodes ->
        (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
            .filter { it.tagName == name }
    }

fun text(parent: Element, name: String): String = childElement(parent, name)?.textContent.orEmpty()
