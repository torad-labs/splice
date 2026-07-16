plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktoml.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Spikes are experiments with receipts, not CI tests: run explicitly with
//   ./gradlew :spikes:test -PrunSpikes [--tests '<Spike>*']
// Each spike writes its own results file under spikes/results/ (the receipt).
tasks.withType<Test>().configureEach {
    enabled = providers.gradleProperty("runSpikes").isPresent
    systemProperty("spike.results.dir", layout.projectDirectory.dir("results").asFile.absolutePath)
    testLogging {
        showStandardStreams = true
    }
}
