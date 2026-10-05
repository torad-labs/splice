plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    api(project(":features-sessions"))
    implementation(project(":integrations-upstream"))
    implementation(project(":integrations-http"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    implementation(libs.zstd.jni) // The v2 trace body pack frames each chunk with zstd
    testImplementation(libs.ktor.server.test.host) {
        // The test host drags in ktor-client-apache5 (httpclient5 5.5.1 / httpcore5 5.3.6 — dependabot
        // alerts #19, #21, #22), an engine no test here uses: testApplication's client is the
        // in-process test engine, and every other test rides CIO. Excluded rather than pinned, so
        // the advisories leave the graph instead of chasing it.
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.okhttp) // V4-456: delayed real upstream writes through the production engine
    testImplementation(project(":integrations-dialects-openai-responses"))
    testImplementation(project(":integrations-dialects-anthropic"))
    testImplementation(project(":integrations-providers-codex"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.platform.launcher)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesImplementation(libs.junit.platform.launcher)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.zstd.jni) // CX-03: the mock decodes zstd like the real upstream
    testFixturesApi(project(":core")) // Public budget and logging types in the shared head builder
    testFixturesApi(project(":integrations-upstream")) // Public admission and transport types in that builder
}

tasks.test {
    systemProperty(
        "junit.jupiter.tempdir.deletion.strategy.default",
        "splice.head.HeadFileWriteCleanup",
    )
}
