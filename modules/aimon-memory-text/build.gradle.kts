plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(project(":aimon-memory-core"))
    implementation(libs.findLibrary("lucene-core").get())
    implementation(libs.findLibrary("lucene-analysis").get())
    implementation(libs.findLibrary("lucene-nori").get())
    implementation(libs.findLibrary("jtokkit").get())
}

dependencies {
    testImplementation(project(":aimon-memory-testkit"))
}
