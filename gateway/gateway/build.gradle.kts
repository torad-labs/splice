plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(project(":dialect-openai-responses"))
    testImplementation(project(":provider-codex"))
    testImplementation(libs.kotlinx.coroutines.test)
}
