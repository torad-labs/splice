// NEW (discipline L4): the FIR compiler-plugin module. An unconsumed @MustConsume value is a
// COMPILE ERROR (MustConsumeDiscardChecker), wired into every other module via -Xplugin in the
// root gateway/build.gradle.kts.
//
// It applies `org.jetbrains.kotlin.jvm` DIRECTLY, not `splice.kotlin-common`: the root build wires
// -Xplugin=<this module's jar> into every kotlin.jvm module, and this module must be the ONE that
// does not consume its own not-yet-built jar (self-application deadlock). It still carries detekt
// (maxIssues:0 like everywhere else) and `splice.module-law` (its emptySet()/nonLibrary entries are
// config-time-checked). Test configs are exempt from the module law, so testImplementation(:core) is legal.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    id("splice.module-law")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The FIR checker API (FirExpressionChecker.check) is declared with context parameters in
        // Kotlin 2.3.x; overriding it requires the feature flag (not on by default at 2.3.21).
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

detekt {
    // The shared law, plus a module-scoped overlay disabling the one ktlint rule that NPEs on Kotlin
    // context parameters (detekt#8140, fixed only in detekt 2.0) — see detekt-context-parameters.yml.
    config.setFrom(
        rootProject.layout.projectDirectory.file("detekt.yml"),
        layout.projectDirectory.file("detekt-context-parameters.yml"),
    )
    buildUponDefaultConfig = true
}

dependencies {
    // compileOnly: at plugin-load time the running compiler already provides these classes; shipping
    // them in the plugin jar would clash with the host compiler's own copies.
    compileOnly(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.compiler.embeddable)
    // the fixtures compiled by the test reference splice.core.annotation.MustConsume; the test JVM's
    // own classpath (java.class.path) carries :core, so -cp resolves the annotation.
    testImplementation(project(":core"))
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

// The root build wires this unpublished compiler plugin into every Kotlin compile via a stable
// -Xplugin path. Project-wide release versioning must not silently change that internal filename:
// a versioned archive leaves an old unversioned jar behind and can make incremental builds pass
// while clean CI fails.
tasks.named<Jar>("jar") {
    archiveFileName.set("fir-checks.jar")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // the test drives K2JVMCompiler with -Xplugin=<this module's jar>; needs it built first and its path.
    dependsOn(tasks.named("jar"))
    val jarPath = tasks.named("jar", Jar::class).flatMap { it.archiveFile }
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dsplice.firChecksPluginJar=${jarPath.get().asFile.absolutePath}")
        },
    )
}
