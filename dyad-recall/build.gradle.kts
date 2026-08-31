dependencies {
    api(project(":dyad-core"))
    api(project(":dyad-store"))
    implementation(project(":dyad-text"))
    implementation("org.springframework:spring-context")

    testImplementation(project(":dyad-testkit"))
    testImplementation(project(":dyad-text"))
}
