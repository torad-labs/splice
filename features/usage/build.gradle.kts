plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(project(":integrations-http"))
    api(project(":features-accounts"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    // `splice perf` lists splice.toml's heads, read-only, never materialized.
    implementation(project(":integrations-topology"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.server.test.host) {
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
}
