plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(project(":integrations-http"))
    api(libs.ktor.server.core)
    // `splice wire` reads the head through the daemon client (WireFetch names its ControlReply) and the
    // head's port from splice.toml.
    api(project(":integrations-daemon-client"))
    implementation(project(":integrations-topology"))
    implementation(libs.kotlinx.serialization.json)
}
