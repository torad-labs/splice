plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-upstream"))
    implementation(project(":integrations-dialects-openai-responses"))
    implementation(project(":integrations-dialects-openai-chat"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(project(":features-turns"))
    testImplementation(testFixtures(project(":features-turns")))
}
