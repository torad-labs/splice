// NEW: shared Kotlin/JVM configuration for every gateway module (P1-GRADLE).
// Kind-specific rules (dependency law, explicitApi) live in splice.module-law.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    jvmToolchain(21)
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
}
