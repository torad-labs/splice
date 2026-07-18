plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
