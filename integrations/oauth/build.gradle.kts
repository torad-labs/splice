plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    // API, not implementation: the public surface names these modules' types — core's RefreshAttempt
    // and AuthKind, upstream's Waiter, each provider's token shape, and ktor's HttpClient.
    api(project(":core"))
    api(project(":integrations-upstream"))
    // Each vendor's sign-in spec and refresh hop speaks its provider's own token shapes; the HTTP
    // half of provider auth lives here rather than in the providers.
    api(project(":integrations-providers-codex"))
    api(project(":integrations-providers-grok"))
    api(project(":integrations-providers-kimi"))
    api(project(":integrations-providers-muse"))
    api(libs.ktor.client.core)
    // StoredCredential expands a credential file's `~` the way the topology loader does.
    implementation(project(":integrations-topology"))
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // TestPorts: a port a test must know before anything binds it, reserved below the ephemeral range.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)
}
