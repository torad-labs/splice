plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(project(":dialect-openai-responses"))
    implementation(project(":dialect-openai-chat"))
    implementation(project(":provider-codex"))
    implementation(project(":provider-grok"))
    implementation(project(":provider-openai"))
    implementation(project(":gateway"))
    implementation(project(":control"))
}
