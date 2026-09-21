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
    ":upstream",
    ":dialects-anthropic",
    ":dialects-openai-responses",
    ":dialects-openai-chat",
    ":providers-codex",
    ":providers-grok",
    ":providers-kimi",
    ":providers-muse",
    ":provider-openai",
    ":daemon-head",
    ":daemon-control",
    ":app",
    ":arch-tests",
    ":fir-checks",
)

project(":core").projectDir = file("core")
// The FIRST module to leave gateway/ (restructure plan §2.3): :client is the Claude Code side.
project(":client").projectDir = file("client")
project(":upstream").projectDir = file("upstream")
project(":dialects-anthropic").projectDir = file("dialects/anthropic")
project(":dialects-openai-responses").projectDir = file("dialects/openai-responses")
project(":dialects-openai-chat").projectDir = file("dialects/openai-chat")
project(":providers-codex").projectDir = file("providers/codex")
project(":providers-grok").projectDir = file("providers/grok")
project(":providers-kimi").projectDir = file("providers/kimi")
project(":providers-muse").projectDir = file("providers/muse")
project(":provider-openai").projectDir = file("gateway/provider-openai")
project(":daemon-head").projectDir = file("daemon/head")
project(":daemon-control").projectDir = file("daemon/control")
project(":app").projectDir = file("gateway/app")
project(":arch-tests").projectDir = file("gateway/arch-tests")
project(":fir-checks").projectDir = file("gateway/fir-checks")
