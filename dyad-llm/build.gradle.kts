dependencies {
    api(project(":dyad-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(project(":dyad-testkit"))
}
