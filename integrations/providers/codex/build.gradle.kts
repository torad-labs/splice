plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-upstream"))
    implementation(project(":integrations-dialects-openai-responses"))
    testImplementation(testFixtures(project(":integrations-dialects-openai-responses")))
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The journal's streaming test loads a journal larger than a child JVM's whole heap; the child runs
// from this module's own test runtime classpath, as :integrations-codemode's worker tests do.
tasks.test {
    systemProperty("codex.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
