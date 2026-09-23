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
}
