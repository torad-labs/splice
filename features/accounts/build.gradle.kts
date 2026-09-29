plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    implementation(project(":integrations-http"))
    // CredentialPresence resolves a credential file's `~` the way splice.toml's loader does.
    implementation(project(":integrations-topology"))
    api(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.core)
    // V4-423: SwitchRoute takes an ApplicationCall, so the refusal is proven through the route itself.
    testImplementation(libs.ktor.server.test.host) {
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
}
