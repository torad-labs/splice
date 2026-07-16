plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    testImplementation(libs.konsist)
}

tasks.withType<Test>().configureEach {
    systemProperty("gateway.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    // Konsist scans the whole tree's sources at runtime — they are real inputs of this
    // task. Without declaring them, Gradle marks the task UP-TO-DATE after unrelated
    // module edits and the laws silently stop running (caught red-handed in P1-KONSIST's
    // first red/green attempt).
    inputs.files(
        rootProject.layout.projectDirectory.asFileTree.matching {
            include("*/src/main/kotlin/**/*.kt")
        },
    ).withPropertyName("scannedProductionSources")
}
