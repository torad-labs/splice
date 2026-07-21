// NEW (discipline L4): wire the :fir-checks compiler plugin into EVERY Kotlin module's compile, so an
// unconsumed @MustConsume value is a COMPILE ERROR tree-wide (not only where the plugin jar is named
// by hand). This is the ONLY place that can reference :fir-checks as a sibling: build-logic is a
// SEPARATE included build and cannot see root subprojects, so splice.kotlin-common cannot do it.
//
// -Xplugin=<absolute jar path> is used deliberately instead of the kotlinCompilerPluginClasspath SPI:
// that SPI expects a PUBLISHED SubpluginArtifact coordinate, the wrong fit for an unpublished sibling.
import groovy.json.JsonSlurper
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
val firChecksPluginJar =
    project(":fir-checks").layout.buildDirectory
        .file("libs/fir-checks.jar")
val firChecksPluginArg = firChecksPluginJar.map { "-Xplugin=${it.asFile.absolutePath}" }
val releaseVersion = (JsonSlurper().parse(file("../package.json")) as Map<*, *>)["version"].toString()

allprojects {
    version = releaseVersion
}

subprojects {
    // :fir-checks must NOT compile against its own not-yet-built jar (self-application deadlock).
    if (path == ":fir-checks") return@subprojects
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<KotlinCompile>().configureEach {
            dependsOn(":fir-checks:jar")
            // firChecksPluginArg (below) is a plain -Xplugin=<path> STRING built from a fixed path,
            // so Gradle tracks it as an opaque value input — byte-identical across builds even when
            // the jar's content changes, letting this task go UP-TO-DATE against a stale checker.
            // dependsOn above only orders execution; it does not make this task's up-to-date check
            // sensitive to the jar's bytes. Register the jar itself as a real file input so editing
            // fir-checks correctly invalidates every consumer's compile. Deliberately NO
            // ClasspathNormalizer here: that normalizer treats the jar as an ordinary library
            // dependency and ignores debug-only bytecode differences (e.g. line numbers) that don't
            // change public ABI — wrong for a compiler PLUGIN, where the compiler loads and runs the
            // whole jar, not just its ABI. The default normalizer content-hashes the raw file, so
            // any byte difference in the jar is a real, tracked input change.
            inputs.files(firChecksPluginJar)
                .withPropertyName("firChecksPluginJar")
            compilerOptions.freeCompilerArgs.add(firChecksPluginArg)
        }
    }
}
