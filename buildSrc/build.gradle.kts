plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Expose the root project's `libs` catalog (gradle/libs.versions.toml) inside the pre-compiled script
// plugins under buildSrc/src/main/kotlin. Without this line a convention plugin cannot reference
// `libs.` accessors at all. See https://github.com/gradle/gradle/issues/15383
dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    // Plugin marker artifacts, so a convention plugin can apply these by id. Versions come from the
    // root catalog for the same reason every other version does: one place to change them.
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:${libs.versions.spotless.get()}")
    implementation(
        "io.spring.dependency-management:io.spring.dependency-management.gradle.plugin:" +
            libs.versions.springDepMgmt.get(),
    )
    implementation(
        "com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:" +
            libs.versions.mavenPublish.get(),
    )
}
