plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)
}
