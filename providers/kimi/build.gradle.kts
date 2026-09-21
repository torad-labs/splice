plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":upstream"))
    implementation(project(":dialects-anthropic"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":dialects-anthropic")))
}
