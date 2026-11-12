plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    api(libs.ktor.server.core)
    // Ingress owns the HTTP/1 decoder-to-Ktor boundary before body copies enter heap channels.
    api(libs.ktor.server.netty)
    // Socket adoption only (AdoptedListeners): the inherited-descriptor channel is public epoll API, so no reflection.
    implementation(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
