// The Gradle root is the REPOSITORY root (restructure plan §6.2 PR 2). The modules still live under
// gateway/<id> until PR 3 moves them, so every include()d project names its directory explicitly
// below — that map is also what the laws grade through (gateway/arch-tests/build.gradle.kts reads
// Gradle's own project model, so a module in a nested directory is graded like any other).
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

rootProject.name = "splice"

include(
    ":core",
    ":client",
    ":provider-spi",
    ":dialect-anthropic-passthrough",
    ":dialect-openai-responses",
    ":dialect-openai-chat",
    ":provider-codex",
    ":provider-grok",
    ":provider-kimi",
    ":provider-muse",
    ":provider-openai",
    ":gateway",
    ":control",
    ":app",
    ":arch-tests",
    ":fir-checks",
)

project(":core").projectDir = file("core")
// The FIRST module to leave gateway/ (restructure plan §2.3): :client is the Claude Code side.
project(":client").projectDir = file("client")
project(":provider-spi").projectDir = file("gateway/provider-spi")
project(":dialect-anthropic-passthrough").projectDir = file("gateway/dialect-anthropic-passthrough")
project(":dialect-openai-responses").projectDir = file("gateway/dialect-openai-responses")
project(":dialect-openai-chat").projectDir = file("gateway/dialect-openai-chat")
project(":provider-codex").projectDir = file("gateway/provider-codex")
project(":provider-grok").projectDir = file("gateway/provider-grok")
project(":provider-kimi").projectDir = file("gateway/provider-kimi")
project(":provider-muse").projectDir = file("gateway/provider-muse")
project(":provider-openai").projectDir = file("gateway/provider-openai")
project(":gateway").projectDir = file("gateway/gateway")
project(":control").projectDir = file("gateway/control")
project(":app").projectDir = file("gateway/app")
project(":arch-tests").projectDir = file("gateway/arch-tests")
project(":fir-checks").projectDir = file("gateway/fir-checks")
