plugins {
    id("splice.kotlin-common")
    id("splice.law-suite")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    implementation(libs.ktoml.core)
}

// V4-315: FeaturesDocumentLawTest parses FEATURES.md 2.3's splice.toml example and checks the release documents. The docs are
// declared inputs of lawTest, so an edit to any of them alone re-runs the law rather than leaving the last green UP-TO-DATE.
val featuresDoc = rootProject.layout.projectDirectory.file(".dev/campaigns/web-console/FEATURES.md")
tasks.named<Test>("lawTest") {
    inputs.file(featuresDoc)
    inputs.files(
        rootProject.file("README.md"),
        rootProject.file("CHANGELOG.md"),
        rootProject.file("app/src/main/resources/splice.example.toml"),
    )
    systemProperty("splice.releaseDocs", rootProject.projectDir.absolutePath)
    systemProperty("splice.featuresDoc", featuresDoc.asFile.absolutePath)
}
