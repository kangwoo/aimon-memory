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
    // For `EmbedConfiguration` alone. The embedders themselves are plain objects and stay that way —
    // `OpenAiEmbedder` takes a record, `HashingEmbedder` takes an int — so this buys the module the
    // ability to say which of the two a deployment gets, without either of them knowing about Spring.
    //
    // It is here rather than in a module above because the choice belongs to the module that owns the
    // implementations. While it lived in `aimon-memory-engine` the only `Embedder` bean in the build
    // was defined two layers above `aimon-memory-recall`, which needs one — so recall could not be
    // assembled without engine, and the coordinate the BOM publishes for it did not stand up alone.
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot")
}

dependencies {
    testImplementation(project(":aimon-memory-testkit"))
}
