plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    api(libs.ktor.server.core)
    // Ingress owns the HTTP/1 decoder-to-Ktor boundary before body copies enter heap channels.
    api(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
}
