// NEW: shared Kotlin/JVM configuration for every gateway module (P1-GRADLE).
// Kind-specific rules (dependency law, explicitApi) live in splice.module-law.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:6.1.2"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
