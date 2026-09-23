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
    // `splice doctor` reads every surface it diagnoses. API: its public surface names accounts'
    // HeadAccountPoolView (the pool read status renders) and upstream's LocalHttp (the local-runtime
    // transport app hands in).
    api(project(":features-accounts"))
    api(project(":integrations-upstream"))
    // The Claude head's wrap state, and the launch shim install wrote.
    implementation(project(":integrations-claude-code"))
    implementation(project(":features-launch"))
    implementation(libs.kotlinx.serialization.json)
}
