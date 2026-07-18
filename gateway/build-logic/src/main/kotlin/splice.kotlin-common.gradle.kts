// NEW: shared Kotlin/JVM configuration for every gateway module (P1-GRADLE).
// Kind-specific rules (dependency law, explicitApi) live in splice.module-law.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Unused-return-value checker (experimental, Kotlin 2.2+): a discarded non-Unit return is a
        // warning — the compiler-level half of the swallow-into-null discipline (the ast-grep wall
        // in checks/ is the write-time half). Promote to error once the codebase is clean.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}

detekt {
    config.setFrom(rootProject.layout.projectDirectory.file("detekt.yml"))
    buildUponDefaultConfig = true
}

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:6.1.2"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    // the kit detekt.yml carries a `formatting:` section (ktlint rules) — needs this plugin
    "detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle's 512m worker default intermittently kills the 1000-stream load test mid-gate
    // (worker dies -> bare java.io.EOFException, 2026-07-18 x2); 1g is bounded and sufficient.
    maxHeapSize = "1g"
}
