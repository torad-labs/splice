plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)
}
