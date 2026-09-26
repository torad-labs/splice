plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":integrations-http"))
    api(libs.ktor.server.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
