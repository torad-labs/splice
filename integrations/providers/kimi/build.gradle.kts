plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-upstream"))
    implementation(project(":integrations-dialects-anthropic"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":integrations-dialects-anthropic")))
}
