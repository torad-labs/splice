// NEW: precompiled convention plugins (P1-GRADLE).
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.get()}")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")

    // TRANSITIVE CVE FLOOR — jackson (2026-07-29). detekt-gradle-plugin pulls
    // jackson-dataformat-xml for its XML report writer, which drags jackson-core/databind onto the
    // PLUGIN classpath at 2.20.1 with 7 open advisories. This is the ONLY place that can fix it:
    // the modules' own `dependencies { constraints { ... } }` cannot reach a plugin classpath
    // (proved during PR #61 — constraining it there added zero verification-metadata entries), and
    // a detekt bump is not available: 1.23.8 IS the latest release on Maven Central.
    //
    // BUILD-TIME ONLY — none of this enters the shipped jar, which is why the gate's verify-OSS-I
    // reported 0 runtime vulnerabilities throughout. The floor is here so the advisory count
    // reflects reality rather than a dependency nobody can act on. Drop it once detekt ships a
    // newer jackson of its own.
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-core:${libs.versions.jackson.get()}")
        implementation("com.fasterxml.jackson.core:jackson-databind:${libs.versions.jackson.get()}")
    }
}
