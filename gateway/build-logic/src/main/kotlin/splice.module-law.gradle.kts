// NEW: THE MODULE LAW (P1-GRADLE) — the dependency graph as configuration-time enforcement.
//
// Doctrine (#924, make drift not compile): hooks and review are probabilistic filters;
// the only wall that holds against an unbounded generator is one where the violation
// is inexpressible. An illegal project dependency here is a BUILD ERROR, not a review
// comment. Pattern lineage: grailseeker's torad.block.ui dependency-law plugin.
//
// The table below IS the architecture diagram. Changing it is changing the architecture:
// do that deliberately, with a ledger note (dev/campaigns/kotlin-gateway.toml).
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** project path -> allowed project-dependency paths. Absent key = unrestricted (:app, :spikes). */
val moduleLaw: Map<String, Set<String>> = mapOf(
    ":core" to emptySet(),
    ":provider-spi" to setOf(":core"),
    ":dialect-anthropic-passthrough" to setOf(":core", ":provider-spi"),
    ":dialect-openai-responses" to setOf(":core", ":provider-spi"),
    ":dialect-openai-chat" to setOf(":core", ":provider-spi"),
    ":provider-codex" to setOf(":core", ":provider-spi", ":dialect-openai-responses"),
    ":provider-grok" to setOf(":core", ":provider-spi", ":dialect-openai-responses"),
    ":provider-kimi" to setOf(":core", ":provider-spi", ":dialect-anthropic-passthrough"),
    ":provider-openai" to setOf(":core", ":provider-spi", ":dialect-openai-responses", ":dialect-openai-chat"),
    ":gateway" to setOf(":core", ":provider-spi"),
    ":control" to setOf(":core"),
    ":arch-tests" to emptySet(),
    // :fir-checks is a Kotlin-compiler plugin: zero project deps in main (it talks to the compiler,
    // not our modules), wired into every build only via the -Xplugin classpath (see gateway/build.gradle.kts).
    ":fir-checks" to emptySet(),
)

/** :core may only reach the kotlin/kotlinx ecosystem — the domain stays framework-free. */
val coreExternalGroups = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx")

/** Modules exempt from explicitApi (executables and test harnesses, not libraries). */
val nonLibrary = setOf(":app", ":spikes", ":arch-tests", ":fir-checks")

// The module law is a MAIN-source architecture rule. Test configs are intentionally NOT covered:
// integration tests legitimately wire sibling modules (e.g. :gateway tests use
// :dialect-openai-responses), and the one genuinely-illegal test dep — a cycle — is already a
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
                            "see gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts " +
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
