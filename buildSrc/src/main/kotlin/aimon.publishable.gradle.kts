import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.vanniktech.maven.publish")
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
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
