plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    testImplementation(libs.ktor.server.test.host) {
        // The test host drags in ktor-client-apache5 (httpclient5 5.5.1 / httpcore5 5.3.6 — dependabot
        // alerts #19, #21, #22), an engine no test here uses: testApplication's client is the
        // in-process test engine, and every other test rides CIO. Excluded rather than pinned, so
        // the advisories leave the graph instead of chasing it.
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
    testImplementation(libs.ktor.client.cio)
    testImplementation(project(":dialect-openai-responses"))
    testImplementation(project(":dialect-anthropic-passthrough"))
    testImplementation(project(":provider-codex"))
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.zstd.jni) // CX-03: the mock decodes zstd like the real upstream
    testFixturesImplementation(project(":core")) // Result.discard on best-effort test-server teardown
}
