// Make the root project's `libs` version catalog visible to buildSrc itself, so
// buildSrc/build.gradle.kts can read `libs.versions.spotless.get()` when it declares the plugin
// marker artifacts the convention plugins apply.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
