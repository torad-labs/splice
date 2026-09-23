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
    ":integrations-claude-code",
    ":integrations-mcp",
    ":integrations-http",
    ":integrations-upstream",
    ":integrations-dialects-anthropic",
    ":integrations-dialects-openai-responses",
    ":integrations-dialects-openai-chat",
    ":integrations-providers-codex",
    ":integrations-providers-grok",
    ":integrations-providers-kimi",
    ":integrations-providers-muse",
    ":integrations-providers-openai",
    ":features-turns",
    ":features-sessions",
    ":features-models",
    ":daemon-control",
    ":features-heads",
    ":features-usage",
    ":app",
    ":quality-architecture",
    ":quality-compiler-plugin",
    ":console",
)

project(":core").projectDir = file("core")
// Reusable client and MCP adapters retain their own compile and visibility boundaries.
project(":integrations-claude-code").projectDir = file("integrations/claude-code")
project(":integrations-mcp").projectDir = file("integrations/mcp")
project(":integrations-http").projectDir = file("integrations/http")
project(":integrations-upstream").projectDir = file("integrations/upstream")
project(":integrations-dialects-anthropic").projectDir = file("integrations/dialects/anthropic")
project(":integrations-dialects-openai-responses").projectDir = file("integrations/dialects/openai-responses")
project(":integrations-dialects-openai-chat").projectDir = file("integrations/dialects/openai-chat")
project(":integrations-providers-codex").projectDir = file("integrations/providers/codex")
project(":integrations-providers-grok").projectDir = file("integrations/providers/grok")
project(":integrations-providers-kimi").projectDir = file("integrations/providers/kimi")
project(":integrations-providers-muse").projectDir = file("integrations/providers/muse")
project(":integrations-providers-openai").projectDir = file("integrations/providers/openai")
project(":features-turns").projectDir = file("features/turns")
project(":features-sessions").projectDir = file("features/sessions")
project(":features-models").projectDir = file("features/models")
project(":daemon-control").projectDir = file("daemon/control")
project(":features-heads").projectDir = file("features/heads")
project(":features-usage").projectDir = file("features/usage")
project(":app").projectDir = file("app")
project(":quality-architecture").projectDir = file("quality/architecture")
project(":quality-compiler-plugin").projectDir = file("quality/compiler-plugin")
// the operator console: a Bun/Vite workspace with no Kotlin, included so the release packages its
// bundle through a task output rather than a checked-in file (PR 4)
project(":console").projectDir = file("console")
