// NEW: the per-module law suite (restructure follow-up to the pre-push split): laws carry @Tag("law")
// and run in lawTest, not test.
// THE LAW SUITE of a module: the tests that read a file outside the module as text (another module's sources, the docs,
// the shipped launch script). Such a test is a law, and it carries @Tag("law") on its WHOLE CLASS. A class must not straddle
// the tag: splice.test-discovery reads each task's JUnit XML and merges a class both tasks report by its LOWER count, so a
// split class is refused as short.
//
// `test` excludes the tag, so a module's unit suite reruns only when its own classpath changes. `lawTest` runs only the tag,
// `check` depends on it, and `lawSuites` requests it for pre-push. A module declares the files its laws read as inputs of its
// `lawTest` task, in its own build script: this plugin cannot know what a law reads.
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("law") }
}

// Resolved here, at script level: inside the task's lambda `extensions` is the task's own container, which has no java extension.
val testSources = extensions.getByType<JavaPluginExtension>().sourceSets.getByName("test")

val lawTest = tasks.register<Test>("lawTest") {
    description = "The laws of this module: the tests that read files outside it. Pre-push runs them through lawSuites."
    group = "verification"
    testClassesDirs = testSources.output.classesDirs
    classpath = testSources.runtimeClasspath
    useJUnitPlatform { includeTags("law") }
    // LawReadGuard (core's testFixtures) is found by the service loader, so every law in this task is guarded.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}

tasks.named("check") { dependsOn(lawTest) }

// The aggregate the pre-push gate requests schedules every project that applies this plugin, with no list to keep: applying the
// plugin IS the registration. `matching` fires whenever the root's `lawSuites` exists, whichever script ran first.
rootProject.tasks.matching { it.name == "lawSuites" }.configureEach { dependsOn(lawTest) }
