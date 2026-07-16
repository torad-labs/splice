plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(project(":dialect-openai-responses"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":gateway"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(testFixtures(project(":gateway")))
}
