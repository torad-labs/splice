// NEW: THE MODULE LAW (P1-GRADLE) — the dependency graph as configuration-time enforcement.
//
// Doctrine (#924, make drift not compile): hooks and review are probabilistic filters;
// the only wall that holds against an unbounded generator is one where the violation
// is inexpressible. An illegal project dependency here is a BUILD ERROR, not a review
// comment. Pattern lineage: grailseeker's torad.block.ui dependency-law plugin.
//
// The table is gradle/module-law.txt and IS the architecture diagram. Changing it is changing the architecture:
// do that deliberately, and say why in the commit.
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import splice.modulelaw.ModuleLawTable

/** project path -> allowed project-dependency paths. Absent key = unrestricted (:app). */
val moduleLaw: Map<String, Set<String>> =
    rootProject.file("gradle/module-law.txt").let { ModuleLawTable.parse(it.readText(), it.path) }

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
                cfg.dependencies.withType<ProjectDependency>().forEach { dep ->
                    val depPath = dep.path
                    check(depPath == project.path || depPath in allowed) {
                        "MODULE LAW: ${project.path} may not depend on $depPath " +
                            "(allowed: ${allowed.sorted()}). The graph is the architecture. " +
                            "see gradle/module-law.txt before touching it."
                    }
                }
            }
    }
    if (project.path == ":core") {
        configurations
            .filter { it.name in lawChecked }
            .forEach { cfg ->
                cfg.dependencies
                    .filter { it !is ProjectDependency }
                    .forEach { dep ->
                        val group = dep.group ?: return@forEach
                        check(coreExternalGroups.any { g -> group == g || group.startsWith("$g.") }) {
                            "MODULE LAW: :core is framework-free, and external dependency " +
                                "${dep.group}:${dep.name} is not in the kotlin/kotlinx allowlist."
                        }
                    }
            }
    }
}
