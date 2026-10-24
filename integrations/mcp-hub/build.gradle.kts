// :integrations-mcp-hub: the MCP hub's two native binaries (docs/specs/mcp-hub.md, step 2), Kotlin/Native on
// linuxX64, so neither is a JVM:
//   splice-mcp-holder  joins one stdio MCP server's stdin and stdout to a Unix socket and outlives the daemon;
//   splice-mcp-front   holds the hub port and every session's stream, checks the bearer and routes calls to holders.
// No JVM module depends on this one. The gate's hub leg, CI and the release link both (splice-platform); the
// install puts them beside splice.jar on Linux x86_64 only, because the hub needs systemd --user (the front's
// socket, each holder's scope).
//
// WHAT REACHES THIS MODULE. The module law applies (the hub depends on no splice module). Everything else the JVM
// modules share is wired to org.jetbrains.kotlin.jvm and does not: splice.kotlin-common (warnings as errors, the
// return-value checker, the detekt and ktlint Gradle tasks), the FIR compiler plugin the root build adds with -Xplugin,
// explicitApi and kover (a JVM library's concerns), and the architecture suite, which reads <module>/src/main. WHAT THE
// HUB GETS INSTEAD: the pre-commit hook runs ktlint, detekt (quality/detekt/detekt.yml) and the ast-grep walls over
// every hub file a commit stages; the gate's hub leg links both binaries; and each pair is qualified against a real
// daemon before it ships. What nothing does: lint a hub file no commit touches, or fail a Kotlin/Native warning. Both
// linters were run over all 13 files on 2026-10-19 (ktlint 1.8.0, detekt 1.23.8, this repo's config) and found nothing,
// so wiring them into this module's `check` is open; it wants a build to show the multiplatform plugin takes them.
plugins {
    kotlin("multiplatform")
    id("splice.module-law")
}

kotlin {
    linuxX64 {
        binaries {
            executable("holder") {
                entryPoint = "splice.hub.holder.main"
                baseName = "splice-mcp-holder"
            }
            executable("front") {
                entryPoint = "splice.hub.front.main"
                baseName = "splice-mcp-front"
            }
        }
    }
    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
