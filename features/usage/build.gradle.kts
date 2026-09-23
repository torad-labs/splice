plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(project(":integrations-http"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.serialization.json)
}
