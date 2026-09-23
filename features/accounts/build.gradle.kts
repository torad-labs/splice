plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    implementation(project(":integrations-http"))
    api(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.core)
}
