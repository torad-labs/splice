plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":upstream"))
    implementation(project(":integrations-dialects-openai-responses"))
    implementation(project(":integrations-dialects-openai-chat"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(project(":daemon-head"))
    testImplementation(testFixtures(project(":daemon-head")))
}
