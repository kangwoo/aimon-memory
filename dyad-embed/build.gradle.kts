// Depends on dyad-text for the tokenizer: truncation has to agree with the batch gate's token
// counts, and two implementations of "how many tokens is this" would eventually disagree.
dependencies {
    api(project(":dyad-core"))
    implementation(project(":dyad-text"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

dependencies {
    testImplementation(project(":dyad-testkit"))
}
