plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.test.host) {
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
    testImplementation(libs.kotlinx.coroutines.test)
}
