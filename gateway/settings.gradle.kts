// NEW: Gradle root for the Kotlin gateway (campaign kotlin-gateway P1-GRADLE).
// Module graph is LAW — see build-logic/src/main/kotlin/splice.module-law.gradle.kts.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "splice-gateway"

include(
    ":core",
    ":provider-spi",
    ":dialect-anthropic-passthrough",
    ":dialect-openai-responses",
    ":dialect-openai-chat",
    ":provider-codex",
    ":provider-grok",
    ":provider-kimi",
    ":provider-openai",
    ":gateway",
    ":control",
    ":app",
    ":spikes",
    ":arch-tests",
)
