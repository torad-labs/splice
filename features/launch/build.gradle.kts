plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(project(":integrations-claude-code"))
    api(project(":integrations-http"))
    api(libs.ktor.server.core)
    // `splice install` reads splice.toml's heads for the wrappers it links; nothing public names it.
    implementation(project(":integrations-topology"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotlinx.coroutines.test)
}
