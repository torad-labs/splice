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
    // Same lesson, second input set (HD-11): the module-dependency-direction law reads the BUILD
    // files, so those are inputs too. Without this the law's own red/green proof came back
    // UP-TO-DATE after a forbidden `project(":gateway")` was added — a green that never ran.
    inputs.files(
        rootProject.layout.projectDirectory.asFileTree.matching {
            include("*/build.gradle.kts")
            include("settings.gradle.kts")
            // V4-91 (2026-09-17): the Konsist map now READS the module law's own map out of
            // build-logic, so an edit there must re-run the laws too, or a stale allowance could
            // come back UP-TO-DATE-green.
            include("build-logic/src/main/kotlin/**/*.kts")
        },
    ).withPropertyName("scannedModuleBuildFiles")
}
