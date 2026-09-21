plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":upstream"))
    implementation(project(":dialect-openai-responses"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":daemon-head"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(testFixtures(project(":daemon-head")))
}
