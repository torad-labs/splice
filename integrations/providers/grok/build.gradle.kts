plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-upstream"))
    implementation(project(":integrations-dialects-openai-responses"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":features-turns"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(testFixtures(project(":features-turns")))
    testImplementation(testFixtures(project(":integrations-dialects-openai-responses")))
}
