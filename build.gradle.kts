// NEW (discipline L4): wire the :quality-compiler-plugin compiler plugin into EVERY Kotlin module's compile, so an
// unconsumed @MustConsume value is a COMPILE ERROR tree-wide (not only where the plugin jar is named
// by hand). This is the ONLY place that can reference :quality-compiler-plugin as a sibling: build-logic is a
// SEPARATE included build and cannot see root subprojects, so splice.kotlin-common cannot do it.
//
// -Xplugin=<absolute jar path> is used deliberately instead of the kotlinCompilerPluginClasspath SPI:
// that SPI expects a PUBLISHED SubpluginArtifact coordinate, the wrong fit for an unpublished sibling.
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// TRANSITIVE CVE FLOOR — freemarker (2026-09-25, GHSA-27j2-h3m2-8237, critical). kover, applied below,
// puts freemarker 2.3.32 on THIS script's classpath through its coverage reporter, so the floor is a
// constraint on this classpath, where the plugin resolves. Drop it once kover's chain carries 2.3.35+.
buildscript {
    dependencies {
        constraints {
            classpath("org.freemarker:freemarker:${libs.versions.freemarker.get()}") {
                because("GHSA-27j2-h3m2-8237; kover 0.9.9's coverage-report pins freemarker 2.3.32")
            }
        }
    }
}

plugins {
    // apply false: put the Kotlin Gradle plugin on THIS build script's classpath (so KotlinCompile is
    // typeable here) without applying it to the root project itself.
    alias(libs.plugins.kotlin.jvm) apply false
    // Coverage visibility: org.jetbrains.kotlinx.kover is applied at the root so the aggregated
    // :koverXmlReport task merges reports from every Kotlin module (kover { merge { allProjects() } }).
    alias(libs.plugins.kover)
    // The gate ladder (gateOfRecord and one Exec task per row of tools/gate/config/ladder.json) —
    // build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts, entered through `bun tools/gate run`.
    id("splice.gate-ladder")
    // V4-68, the wall that compares DECLARED @Test/@ParameterizedTest/@TestFactory methods against
    // the JUnit XML that says what RAN — build-logic/src/main/kotlin/splice.test-discovery.gradle.kts,
    // which registers verifyTestDiscovery and attaches it to gateOfRecord. It replaces the two
    // `bun checks/config/tests-are-discovered.ts` rows the ladder carried until now.
    id("splice.test-discovery")
}

// The plugin jar's path is derived from :quality-compiler-plugin' build layout (default archive name
// `fir-checks.jar`), resolved lazily — referencing the sibling's `jar` task at root-configuration
// time fails because :quality-compiler-plugin is not evaluated yet. Ordering is guaranteed by the string
// task-dependency `:quality-compiler-plugin:jar` below.
val firChecksPluginJar =
    project(":quality-compiler-plugin").layout.buildDirectory
        .file("libs/fir-checks.jar")
val firChecksPluginArg = firChecksPluginJar.map { "-Xplugin=${it.asFile.absolutePath}" }
// CLOSED WHEN. A `when` over a sealed type, an enum or a Boolean lists every case, so adding a case is a compile error at
// each decision instead of a silent fall into an `else`. The check is a FIR extension in the plugin above, switched per
// module while the codebase is brought across; when every module is in the list the list and the switch go, and the check
// is simply on.
val closedWhenModules =
    setOf(
        ":integrations-terminal",
        ":integrations-providers-muse",
        ":integrations-mcp",
        ":integrations-providers-grok",
        ":integrations-http",
        ":integrations-daemon-client",
        ":features-heads",
        ":features-accounts",
        ":integrations-topology",
        ":features-launch",
        ":features-models",
        ":integrations-dialects-openai-chat",
        ":integrations-dialects-anthropic",
        ":integrations-oauth",
        ":features-lifecycle",
        ":features-usage",
        ":integrations-codemode",
        ":features-sessions",
        ":features-configuration",
        ":core",
        ":integrations-claude-code",
    )
val closedWhenArgs = listOf("-P", "plugin:splice.fir-checks:closedWhen=true")
val releaseVersion = (JsonSlurper().parse(file("package.json")) as Map<*, *>)["version"].toString()

allprojects {
    version = releaseVersion
}

subprojects {
    // :quality-compiler-plugin must NOT compile against its own not-yet-built jar (self-application deadlock).
    if (path == ":quality-compiler-plugin") return@subprojects
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<KotlinCompile>().configureEach {
            dependsOn(":quality-compiler-plugin:jar")
            // firChecksPluginArg (below) is a plain -Xplugin=<path> STRING built from a fixed path,
            // so Gradle tracks it as an opaque value input — byte-identical across builds even when
            // the jar's content changes, letting this task go UP-TO-DATE against a stale checker.
            // dependsOn above only orders execution; it does not make this task's up-to-date check
            // sensitive to the jar's bytes. Register the jar itself as a real file input so editing
            // fir-checks correctly invalidates every consumer's compile. Deliberately NO
            // ClasspathNormalizer here: that normalizer treats the jar as an ordinary library
            // dependency and ignores debug-only bytecode differences (e.g. line numbers) that don't
            // change public ABI — wrong for a compiler PLUGIN, where the compiler loads and runs the
            // whole jar, not just its ABI. The default normalizer content-hashes the raw file, so
            // any byte difference in the jar is a real, tracked input change.
            inputs.files(firChecksPluginJar)
                .withPropertyName("firChecksPluginJar")
            compilerOptions.freeCompilerArgs.add(firChecksPluginArg)
            if (project.path in closedWhenModules) compilerOptions.freeCompilerArgs.addAll(closedWhenArgs)
        }
    }
}

// Kover is applied versionless to every Kotlin JVM module from the root. Each module still resolves
// the version through the catalog alias in the root plugins{} block, and the root `kover { merge }`
// configuration aggregates all subproject reports into one XML at :koverXmlReport.
//
// COVERAGE ON REQUEST. Kover's agent instruments every class a test JVM loads, and nothing in the gate reads coverage: no
// verification rule is set, and the only reader is the coverage workflow's :koverXmlReport. So a test JVM carries the agent
// only when a kover task was asked for by name. Measured on four modules' test tasks, Oct 9: 42.1 s with the agent, 36.5 s
// without it.
val coverageRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("kover") }
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jetbrains.kotlinx.kover")
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            currentProject { instrumentation { disabledForAll.set(!coverageRequested) } }
        }
    }
}

// TRANSITIVE CVE FLOOR (2026-07-27). netty rides in under ktor-server-netty and is declared
// nowhere — which is exactly why Dependabot could not fix its alerts: every security-update job
// died with `security_update_dependency_not_found`, having no declaration to edit, so no PR was
// ever opened and the alerts simply sat there. A constraint is the narrowest fix that both raises
// the resolved version and gives Dependabot a line to bump next time.
//
// A constraint is a FLOOR, not a force: a future ktor shipping a newer netty still wins normally
// rather than being dragged backwards. Constraints apply only to configurations that already
// resolve these modules, so nothing new is pulled onto any classpath.
//
// The jackson alerts are deliberately NOT handled here — jackson 2.20.1 is on the PLUGIN
// classpath via detekt's jackson-dataformat-xml, which subproject constraints cannot reach
// (proved: constraining it added zero verification-metadata entries). Build-time only, never
// shipped; it needs a plugin-classpath constraint or a detekt bump instead.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencies {
            constraints {
                listOf(
                    "io.netty:netty-codec",
                    "io.netty:netty-codec-base",
                    "io.netty:netty-codec-http",
                    "io.netty:netty-codec-http2",
                    "io.netty:netty-codec-compression",
                ).forEach {
                    add("implementation", "$it:${libs.versions.netty.get()}")
                    // Ingress exports Netty types, so its API must carry the existing security floor.
                    if (path == ":integrations-http") add("api", "$it:${libs.versions.netty.get()}")
                }
            }
        }
    }
}

kover {
    merge {
        allProjects()
    }
}
