plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    implementation(libs.ktoml.core)
}

// V4-315: FeaturesTopologyExampleTest parses FEATURES.md 2.3's splice.toml example. The doc is a
// declared input, so an edit to it alone re-runs the test rather than leaving the last green
// UP-TO-DATE.
val featuresDoc = rootProject.layout.projectDirectory.file(".dev/campaigns/web-console/FEATURES.md")
tasks.withType<Test>().configureEach {
    inputs.file(featuresDoc)
    systemProperty("splice.featuresDoc", featuresDoc.asFile.absolutePath)
}
