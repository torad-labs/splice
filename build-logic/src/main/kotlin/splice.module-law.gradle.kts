// NEW: THE MODULE LAW (P1-GRADLE) — the dependency graph as configuration-time enforcement.
//
// Doctrine (#924, make drift not compile): hooks and review are probabilistic filters;
// the only wall that holds against an unbounded generator is one where the violation
// is inexpressible. An illegal project dependency here is a BUILD ERROR, not a review
// comment. Pattern lineage: grailseeker's torad.block.ui dependency-law plugin.
//
// The table below IS the architecture diagram. Changing it is changing the architecture:
// do that deliberately, with a ledger note (.dev/campaigns/kotlin-gateway.toml).
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** project path -> allowed project-dependency paths. Absent key = unrestricted (:app). */
val moduleLaw: Map<String, Set<String>> = mapOf(
    ":core" to emptySet(),
    // the Claude Code side: isolated client state plus the concrete transcript reader. It speaks core
    // and implements the sessions-owned transcript port; no feature implementation points back here.
    ":integrations-claude-code" to setOf(":core", ":features-sessions"),
    ":integrations-mcp" to setOf(":core", ":integrations-claude-code", ":integrations-http"),
    ":integrations-http" to setOf(":core"),
    // the splice.toml file on disk: load, first-run starter, structural preflight and digest.
    ":integrations-topology" to setOf(":core"),
    // the operator-terminal toolkit (menus, spinner, raw mode, the wizard frame): the CLI's terminal
    // adapter, reading keys and drawing on the stream it is handed. Core only, so no daemon module can
    // reach a terminal through it.
    ":integrations-terminal" to setOf(":core"),
    // a CLI process's client of the local daemon: where it listens (control port, supervisor unit),
    // how to prove the caller (the management key), whether it is up (/health, the bound port), and
    // the loopback calls. The daemon side of those routes is app's; this is only ever the asker.
    ":integrations-daemon-client" to setOf(":core", ":integrations-topology"),
    // code mode's GraalJS worker pool: the child-JVM runtime behind the upstream-owned CodeModeRuntime port.
    ":integrations-codemode" to setOf(":core", ":integrations-upstream"),
    // the OAuth sign-in flows, account files and each vendor's refresh hop: the HTTP half of provider
    // auth, beside the HTTP-client-agnostic providers whose token shapes it speaks.
    ":integrations-oauth" to setOf(
        ":core", ":integrations-upstream", ":integrations-providers-codex", ":integrations-providers-grok",
        ":integrations-providers-kimi", ":integrations-providers-muse",
    ),
    ":integrations-upstream" to setOf(":core"),
    ":integrations-dialects-anthropic" to setOf(":core", ":integrations-upstream"),
    ":integrations-dialects-openai-responses" to setOf(":core", ":integrations-upstream"),
    ":integrations-dialects-openai-chat" to setOf(":core", ":integrations-upstream"),
    ":integrations-providers-codex" to setOf(":core", ":integrations-upstream", ":integrations-dialects-openai-responses"),
    ":integrations-providers-grok" to setOf(":core", ":integrations-upstream", ":integrations-dialects-openai-responses"),
    ":integrations-providers-kimi" to setOf(":core", ":integrations-upstream", ":integrations-dialects-anthropic"),
    ":integrations-providers-muse" to setOf(":core", ":integrations-upstream"),
    ":integrations-providers-openai" to setOf(":core", ":integrations-upstream", ":integrations-dialects-openai-responses", ":integrations-dialects-openai-chat"),
    ":features-turns" to setOf(":core", ":integrations-upstream", ":integrations-http", ":features-sessions"),
    ":features-sessions" to setOf(":core", ":integrations-http"),
    ":features-models" to setOf(":core"),
    ":features-heads" to setOf(":core"),
    // sign-in, refresh, the account pool and their console routes; the pool is a read model every
    // operator surface renders.
    ":features-accounts" to setOf(":core", ":integrations-http"),
    ":features-usage" to setOf(":core", ":integrations-http", ":features-accounts"),
    // the daemon's own lifecycle: the draining restart and the upgrade surface.
    ":features-lifecycle" to emptySet(),
    // the doctor report, the one-prompt playground, and the operator's reads of a running head
    // (`splice wire`) through the daemon client.
    ":features-diagnostics" to setOf(
        ":core", ":integrations-http", ":integrations-daemon-client", ":integrations-topology",
    ),
    // launching Claude Code against a head: the exec recipe, the Claude head's wrap, the resume hook,
    // and the wrapper commands `splice install` links to the launch shim from splice.toml's heads.
    ":features-launch" to setOf(":core", ":integrations-claude-code", ":integrations-http", ":integrations-topology"),
    // the daemon's knobs and splice.toml, read and written as data.
    ":features-configuration" to setOf(":core", ":integrations-http"),
    // the console's live event stream: the bus, its event shapes, and GET /api/events.
    ":features-events" to setOf(":integrations-http"),
    ":quality-architecture" to emptySet(),
    // :console is the Bun/Vite operator console — no Kotlin, no edges; graded here so the map
    // covers every module the build declares.
    ":console" to emptySet(),
    // :quality-compiler-plugin is a Kotlin-compiler plugin: zero project deps in main (it talks to the compiler,
    // not our modules), wired into every build only via the -Xplugin classpath (see gateway/build.gradle.kts).
    ":quality-compiler-plugin" to emptySet(),
)

/** :core may only reach the kotlin/kotlinx ecosystem — the domain stays framework-free. */
val coreExternalGroups = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx")

/** Modules exempt from explicitApi (executables and test harnesses, not libraries). */
val nonLibrary = setOf(":app", ":quality-architecture", ":quality-compiler-plugin")

// The module law is a MAIN-source architecture rule. Test configs are intentionally NOT covered:
// integration tests legitimately wire sibling modules (e.g. :daemon-head tests use
// :dialects-openai-responses), and the one genuinely-illegal test dep — a cycle — is already a
// Gradle build error. (The plan's "cover test configs" was reverted for this reason.)
val lawChecked = setOf("api", "implementation", "compileOnly", "runtimeOnly")

if (project.path !in nonLibrary) {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            explicitApi()
        }
    }
}

afterEvaluate {
    val allowed = moduleLaw[project.path]
    if (allowed != null) {
        configurations
            .filter { it.name in lawChecked }
            .forEach { cfg ->
                cfg.dependencies.withType(ProjectDependency::class.java).forEach { dep ->
                    val depPath = dep.path
                    check(depPath == project.path || depPath in allowed) {
                        "MODULE LAW: ${project.path} may not depend on $depPath " +
                            "(allowed: ${allowed.sorted()}). The graph is the architecture — " +
                            "see build-logic/src/main/kotlin/splice.module-law.gradle.kts " +
                            "and the kotlin-gateway campaign ledger before touching it."
                    }
                }
            }
    }
    if (project.path == ":core") {
        configurations
            .filter { it.name in lawChecked }
            .forEach { cfg ->
                cfg.dependencies
                    .filter { it !is ProjectDependency && it.group != null }
                    .forEach { dep ->
                        check(coreExternalGroups.any { g -> dep.group == g || dep.group!!.startsWith("$g.") }) {
                            "MODULE LAW: :core is framework-free — external dependency " +
                                "${dep.group}:${dep.name} is not in the kotlin/kotlinx allowlist."
                        }
                    }
            }
    }
}
