plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    api(project(":aimon-memory-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(project(":aimon-memory-testkit"))
}
