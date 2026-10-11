plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(libs.ktor.server.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.test.host) {
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
    testImplementation(libs.kotlinx.coroutines.test)
}
