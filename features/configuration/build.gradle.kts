plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(project(":integrations-http"))
    // AddModelsVerb takes the prompt toolkit's two prompts; app builds them on its own terminal.
    api(project(":integrations-terminal"))
    api(libs.ktor.server.core)
    // `splice add`: the topology loader, the stored-credential reader, the control port, and the
    // credential presence setup, status and doctor also read.
    implementation(project(":integrations-topology"))
    implementation(project(":integrations-oauth"))
    implementation(project(":integrations-daemon-client"))
    implementation(project(":features-accounts"))
    implementation(libs.kotlinx.serialization.json)
}
