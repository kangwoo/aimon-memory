// Depends on aimon-memory-text for the tokenizer: truncation has to agree with the batch gate's token
// counts, and two implementations of "how many tokens is this" would eventually disagree.

plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    api(project(":aimon-memory-core"))
    implementation(project(":aimon-memory-text"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

dependencies {
    testImplementation(project(":aimon-memory-testkit"))
}
