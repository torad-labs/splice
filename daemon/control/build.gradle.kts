plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-claude-code"))
    api(project(":integrations-mcp"))
    implementation(project(":integrations-http"))
    implementation(project(":features-heads"))
    api(project(":features-sessions"))
    api(project(":features-usage"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    // V4-133: POST /api/alerts/test's one outbound webhook POST. The Java engine, not CIO — the
    // same choice AuthHttpClientFactory (:app) made and the same reason: CIO's socket writer
    // busy-spins on a non-writable socket. Already a runtime dependency of :app at this version;
    // this only extends which module may use it from main sources.
    implementation(libs.ktor.client.java)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotlinx.coroutines.test)
}
