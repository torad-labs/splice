// NEW (discipline L4): wire the :fir-checks compiler plugin into EVERY Kotlin module's compile, so an
// unconsumed @MustConsume value is a COMPILE ERROR tree-wide (not only where the plugin jar is named
// by hand). This is the ONLY place that can reference :fir-checks as a sibling: build-logic is a
// SEPARATE included build and cannot see root subprojects, so splice.kotlin-common cannot do it.
//
// -Xplugin=<absolute jar path> is used deliberately instead of the kotlinCompilerPluginClasspath SPI:
// that SPI expects a PUBLISHED SubpluginArtifact coordinate, the wrong fit for an unpublished sibling.
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // apply false: put the Kotlin Gradle plugin on THIS build script's classpath (so KotlinCompile is
    // typeable here) without applying it to the root project itself.
    alias(libs.plugins.kotlin.jvm) apply false
}

// The plugin jar's path is derived from :fir-checks' build layout (default archive name
// `fir-checks.jar`), resolved lazily — referencing the sibling's `jar` task at root-configuration
// time fails because :fir-checks is not evaluated yet. Ordering is guaranteed by the string
// task-dependency `:fir-checks:jar` below.
val firChecksPluginArg =
    project(":fir-checks").layout.buildDirectory
        .file("libs/fir-checks.jar")
        .map { "-Xplugin=${it.asFile.absolutePath}" }

subprojects {
    // :fir-checks must NOT compile against its own not-yet-built jar (self-application deadlock).
    if (path == ":fir-checks") return@subprojects
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<KotlinCompile>().configureEach {
            dependsOn(":fir-checks:jar")
            compilerOptions.freeCompilerArgs.add(firChecksPluginArg)
        }
    }
}
