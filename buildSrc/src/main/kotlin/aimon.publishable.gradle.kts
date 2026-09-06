import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.vanniktech.maven.publish")
}

configure<MavenPublishBaseExtension> {
    // No argument, and there is no longer one to give. `publishToMavenCentral` took a `SonatypeHost`
    // up to 0.33.x — this build passed `CENTRAL_PORTAL` — and the plugin removed the enum in 0.34.0
    // once OSSRH shut down and the portal became the only host it can publish to. The destination is
    // unchanged; it is just no longer something a caller states.
    publishToMavenCentral()
    signAllPublications()
}

// Two kinds of thing are published from this build, and what a publication contains depends on which one
// this is. They are mutually exclusive by construction — Gradle refuses `java-platform` alongside
// `java-library` — so this is a choice, not a merge. Written with `plugins.withId` rather than an `if` so
// the order of a module's own `plugins { }` block cannot change the answer.
plugins.withId("java-library") {
    configure<MavenPublishBaseExtension> {
        configure(
            JavaLibrary(
                javadocJar = JavadocJar.Javadoc(),
                sourcesJar = true,
            ),
        )
    }

    // Write the versions this build actually resolved into the POM.
    //
    // No module declares a version for anything Spring's dependency-management supplies — that is the point
    // of using it — so without this the generated POM carries those dependencies with no version at all.
    // `aimon-memory-store` published eleven dependencies of which seven had none: postgresql, jackson,
    // flyway, spring-jdbc. Gradle consumers survive on the module metadata; a Maven consumer reads the POM
    // and cannot resolve it.
    //
    // Gradle only ever complained about `aimon-memory-core`, and that is a coincidence of its shape rather
    // than the extent of the problem. The validation fires when a publication's dependencies are *all*
    // versionless, and core is the only module declaring none of its own — its single published dependency
    // is the `slf4j-api` the conventions add. Everything else slipped past the check with a POM that was
    // already wrong, and core being the root the other eight depend on is the only reason none of it
    // reached Central.
    extensions.configure<PublishingExtension> {
        publications.withType(MavenPublication::class.java).configureEach {
            versionMapping {
                usage("java-api") { fromResolutionResult() }
                usage("java-runtime") { fromResolutionResult() }
            }
        }
    }
}

plugins.withId("java-platform") {
    configure<MavenPublishBaseExtension> {
        // A platform has no code, so there is no javadoc and no sources to attach: the POM is the whole
        // artifact. It still has to be signed, which the block above already arranges.
        configure(JavaPlatform())
    }
}

// Applying this to anything else would produce a publication with nothing in it, and the first evidence
// of that would be an empty artifact on Central. Say it here instead.
afterEvaluate {
    check(plugins.hasPlugin("java-library") || plugins.hasPlugin("java-platform")) {
        "Project '$path' applies aimon.publishable but is neither a java-library nor a java-platform, " +
            "so there is nothing for it to publish."
    }
}
