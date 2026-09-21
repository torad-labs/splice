plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":upstream"))
    implementation(project(":dialects-openai-responses"))
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
