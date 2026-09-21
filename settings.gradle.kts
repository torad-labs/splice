// The Gradle root is the REPOSITORY root (restructure plan §6.2 PR 2) and every module lives at the
// directory its id derives from (`:daemon-head` -> daemon/head; restructure plan §1.3, PR 3). Each
// include()d project still names its directory explicitly below — that map is what the laws grade
// through (quality/architecture/build.gradle.kts reads Gradle's own project model), and the
// id-derivation law in ModuleLawsTest fails the build when an id and its directory disagree.
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
    ":providers-openai",
    ":daemon-head",
    ":daemon-control",
    ":app",
    ":quality-architecture",
    ":quality-compiler-plugin",
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
project(":providers-openai").projectDir = file("providers/openai")
project(":daemon-head").projectDir = file("daemon/head")
project(":daemon-control").projectDir = file("daemon/control")
project(":app").projectDir = file("app")
project(":quality-architecture").projectDir = file("quality/architecture")
project(":quality-compiler-plugin").projectDir = file("quality/compiler-plugin")
