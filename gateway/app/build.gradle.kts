plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    application
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(project(":dialect-openai-responses"))
    implementation(project(":dialect-openai-chat"))
    implementation(project(":provider-codex"))
    implementation(project(":provider-grok"))
    implementation(project(":provider-kimi"))
    implementation(project(":provider-openai"))
    implementation(project(":gateway"))
    implementation(project(":control"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktoml.core)
    implementation(libs.ktor.client.java)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":gateway")))
}

application {
    applicationName = "splice"
    mainClass.set("splice.app.MainKt")
}
