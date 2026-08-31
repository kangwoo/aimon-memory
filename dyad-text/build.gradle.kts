val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(project(":dyad-core"))
    implementation(libs.findLibrary("lucene-core").get())
    implementation(libs.findLibrary("lucene-analysis").get())
    implementation(libs.findLibrary("lucene-nori").get())
    implementation(libs.findLibrary("jtokkit").get())
}

dependencies {
    testImplementation(project(":dyad-testkit"))
}
