plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":upstream"))
    implementation(project(":dialects-openai-responses"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":daemon-head"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(testFixtures(project(":daemon-head")))
    testImplementation(testFixtures(project(":dialects-openai-responses")))
}
