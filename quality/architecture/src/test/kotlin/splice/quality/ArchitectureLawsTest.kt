// NEW: Konsist architecture laws (P1-KONSIST) — ring 3 of the enforcement stack.
// These arm as code lands: an empty scope passes vacuously, a violation fails :quality-architecture:test.
// Grow this file as modules land; every new law lands with its red/green proof in the commit message.
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** DR-165: every module that ACTUALLY ships production Kotlin, read off the tree. This is the
 *  denominator the coverage laws use — the module-direction law already derives its own from
 *  the build, so no coverage law trusts a hand-authored list.
 *
 *  P0: the modules come from the PROJECT MAP and their sources from the directory the map gives,
 *  not from the root's immediate child directories. The old walk could only ever see a module that
 *  is a direct child of the Gradle root, so a module under integrations/providers/ would drop out of the
 *  denominator and stop being graded with nothing going red. */
private fun productionModules(map: ProjectMap): Set<String> =
    map.modules
        .filter { module ->
            val main = map.mainSources(module)
            main.isDirectory && main.walkTopDown().any { it.isFile && it.extension == "kt" }
        }
        .toSet()

/** DR-165: one *RequestBuilder module's contract verdict, or null when it is fully covered. PURE for
 *  being a pure function: every live builder module already ships both halves,
 *  so the tree cannot red the CONSUMER half, and an addition the tree cannot falsify would be the
 *  exact defect this row repairs. The fixture below is what makes it real. */
private fun contractViolation(module: String, hasFixture: Boolean, hasConsumer: Boolean): String? = when {
    !hasFixture -> "$module ships a *RequestBuilder but no src/test/resources/contract/<name>.json"
    !hasConsumer ->
        "$module has a contract fixture but no *ContractTest.kt reading it — a golden " +
            "nothing compares against pins nothing"
    else -> null
}

/** HD-9 (#924 capstone): the files allowed to directly construct
 *  [splice.dialect.anthropic.PassthroughQuirks]. A quirks profile is one provider's wire
 *  deformation set (Kimi's adaptive-thinking map, Muse's tool-name cap); letting any file build
 *  one lets provider identity leak into whichever module happens to need a header tweak. Only the
 *  class's own module and the code that assembles a head's provider may build one. Tests are
 *  exempt by construction — the walk below reads only `src/main`.
 *
 *  STALE POINTER CORRECTED: the row's own briefing named `Daemon.kt` as the head-assembly entry
 *  point. Daemon.kt never constructs a PassthroughQuirks — it delegates entirely to
 *  `ManagedHeadFactory` -> `ProviderAssembly`. Measured 2026-09-20
 *  (`grep -rn "PassthroughQuirks(" gateway/PROJECT/src/main` over every module): the three live
 *  call sites are `app/.../provider/PassthroughArm.kt` (client + unregistered API-key heads),
 *  `app/.../provider/MusePassthroughArm.kt` (Muse's tool-name-cap profile) and
 *  `integrations/providers/kimi/.../KimiQuirks.kt` (Kimi's own deformation set) — the head-assembly provider
 *  package, plus the one provider module that builds its own profile rather than taking the
 *  neutral one. */
private val PASSTHROUGH_QUIRKS_ALLOWED_SITES = mapOf(
    ":integrations-dialects-anthropic" to "src/main/", // the class's own module
    ":app" to "src/main/kotlin/splice/app/provider/", // head assembly: ProviderAssembly + its arms
    ":integrations-providers-kimi" to "src/main/", // Kimi's own deformation profile (KimiQuirks.kt)
)

/** P0: the allowed sites as root-relative path prefixes, resolved through the project map — the
 *  module's IDENTITY is what carries the allowance, so a module that moves keeps it without an
 *  edit here, and a module the build drops fails by name instead of quietly widening the law. */
private fun passthroughQuirksAllowedPrefixes(map: ProjectMap): List<String> =
    PASSTHROUGH_QUIRKS_ALLOWED_SITES.map { (module, inModule) -> "${map.relativeDir(module)}/$inModule" }

/** HD-9: one file's PassthroughQuirks-construction violations, as violation lines. PURE so it can
 *  be proven against synthetic input — the live tree is clean today, so a silently-deleted matcher
 *  would look identical to a passing law. The declaring `class PassthroughQuirks(` line is not a
 *  construction and is skipped so the class's own file never self-reports. */
private fun passthroughQuirksConstructionViolations(
    path: String,
    text: String,
    allowedPrefixes: List<String>,
): List<String> {
    if (allowedPrefixes.any { path.startsWith(it) }) return emptyList()
    val violations = mutableListOf<String>()
    text.lineSequence().forEachIndexed { index, line ->
        if (line.contains("class PassthroughQuirks(")) return@forEachIndexed
        if (line.contains("PassthroughQuirks(")) {
            violations += "$path:${index + 1} constructs PassthroughQuirks outside its allowed sites " +
                "(${allowedPrefixes.joinToString()}) — a provider's deformation " +
                "profile belongs to the module that owns the provider or to head assembly, not to " +
                "whichever file happens to need it."
        }
    }
    return violations
}

/** HD-9 (#924 capstone): every production file under `src/main`, across every module that ships
 *  one — the SOURCE-derived denominator [passthroughQuirksConstructionViolations] is graded
 *  against, reusing [productionModules] rather than a hand list for the same DR-165 reason. */
private fun allProductionFiles(map: ProjectMap): List<File> =
    productionModules(map).sorted().flatMap { module ->
        map.mainSources(module).walkTopDown().filter { it.isFile && it.extension == "kt" }
    }

/** HD-9: the dialect modules — [productionModules] filtered to the `:dialects-*` adapters. P0: the
 *  filter is on the module's Gradle PATH, which survives the module moving into integrations/dialects/. */
private fun dialectModules(map: ProjectMap): Set<String> =
    productionModules(map).filter { it.startsWith(":integrations-dialects-") }.toSet()

/** HD-9 (#924 capstone): dialects adapt ONE wire format; [splice.core.topology] is the
 *  operator-facing head/provider registry (TOML parsing, quirks-config overlays), and a dialect
 *  that imports it can react to raw operator config directly instead of the typed request it is
 *  handed — the same layering violation `core stays framework-free` guards one level up, applied
 *  to the boundary directly above it. PURE for the same synthetic-proof reason as the
 *  construction guard above. */
private fun topologyImportViolations(path: String, text: String): List<String> {
    val violations = mutableListOf<String>()
    text.lineSequence().forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("import ")) return@forEachIndexed
        val imported = trimmed.removePrefix("import ").trim().removeSuffix(";")
        if (imported == "splice.core.topology" || imported.startsWith("splice.core.topology.")) {
            violations += "$path:${index + 1} imports $imported — dialects adapt one wire format and " +
                "must not read topology/operator config directly; take what you need as a typed " +
                "parameter instead."
        }
    }
    return violations
}

class ArchitectureLawsTest {

    // P0: the module set and every module's directory come from the BUILD, through one channel
    // that fails by name when it is absent — see ProjectMap.kt.
    private val map = ProjectMap.fromSystemProperties()

    // Konsist resolves scopeFromDirectory RELATIVE to the Gradle root it detects;
    // absolute paths get prefixed and blow up (caught in this law's first red/green).
    // P0: the directory is the map's, so the relative path stays correct for a module that is not
    // a direct child of the root.
    private fun mainScope(module: String) =
        Konsist.scopeFromDirectory("${map.relativeDir(module)}/src/main/kotlin")

    // DR-165: the CONSUMER half of the contract law, proven against synthetic input for the same
    // reason. Every live *RequestBuilder module already ships both a fixture and a *ContractTest, so
    // nothing in the tree can red it — and a guard the tree cannot falsify is the shape this row
    // exists to remove, not one it may quietly add.
    @Test
    fun `the contract-coverage guard can actually fail - DR-165`() {
        assertEquals(null, contractViolation(":dialect-x", hasFixture = true, hasConsumer = true))
        assertEquals(
            ":dialect-x ships a *RequestBuilder but no src/test/resources/contract/<name>.json",
            contractViolation(":dialect-x", hasFixture = false, hasConsumer = true),
            "a builder with no fixture is the #924 Phase 1 case",
        )
        assertEquals(
            ":dialect-x has a contract fixture but no *ContractTest.kt reading it — a golden " +
                "nothing compares against pins nothing",
            contractViolation(":dialect-x", hasFixture = true, hasConsumer = false),
            "a fixture with no consumer is the fail-open one layer down",
        )
    }

    @Test
    fun `core stays framework-free - no ktor imports in core`() {
        val dir = map.mainSources(":core")
        if (!dir.exists()) return
        mainScope(":core").imports.assertTrue { !it.name.startsWith("io.ktor") }
    }

    @Test
    fun `core wire types are serializable`() {
        val dir = map.mainSources(":core")
        if (!dir.exists()) return
        mainScope(":core")
            .classes()
            .filter { it.resideInPackage("..wire..") }
            .assertTrue { cls -> cls.annotations.any { it.name.endsWith("Serializable") } }
    }

    // C3 coverage-by-law (#924 Phase 1): the request-byte contract is not opt-in. Every module that
    // ships a *RequestBuilder must also ship at least one contract/<name>.json golden — so a new
    // dialect arrives WITH its exact-request-bytes fixture (the stream_options / gzip incident class
    // becomes a failing unit test) rather than un-pinned. The receipt-binding half (a changed golden
    // must match a live-200 receipt) activates on traffic; see docs/architecture/request-byte-contracts.md.
    // DR-165: the builder modules come from the SOURCE TREE, not from a hand-written list. Derived
    // from the allowlist, this law could be switched off for a whole dialect by deleting one string
    // — codex-splice's mutant removed dialect-openai-responses and the suite stayed green 16/16,
    // so that module's request bytes went unpinned while the law still reported coverage.
    //
    // It also now requires a live CONSUMER, not only a fixture: a contract/<name>.json that no
    // *ContractTest reads is a golden nothing compares against — the same fail-open one layer down,
    // and the reason a fixture alone was never the guarantee this law claims to give.
    @Test
    fun `every RequestBuilder module ships a request-byte contract fixture and a test that reads it`() {
        val builderModules = productionModules(map).filter { module ->
            val mainDir = map.mainSources(module)
            mainDir.isDirectory && mainDir.walkTopDown().any { it.isFile && it.name.endsWith("RequestBuilder.kt") }
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            builderModules.isNotEmpty(),
            "expected at least one *RequestBuilder module — did the module layout change?",
        )
        val violations = builderModules.mapNotNull { module ->
            val contractDir = File(map.dir(module), "src/test/resources/contract")
            val hasFixture =
                contractDir.isDirectory && !contractDir.listFiles { f -> f.extension == "json" }.isNullOrEmpty()
            val testDir = File(map.dir(module), "src/test/kotlin")
            val hasConsumer = testDir.isDirectory &&
                testDir.walkTopDown().any { it.isFile && it.name.endsWith("ContractTest.kt") }
            contractViolation(module, hasFixture, hasConsumer)
        }
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "REQUEST-BYTE CONTRACT COVERAGE (#924 Phase 1, DR-165) violated:\n  - ",
                postfix = "\nSee docs/architecture/request-byte-contracts.md.",
            )
        }
    }

    // HD-9 (#924 capstone): PassthroughQuirks is a provider's deformation profile; only the class's
    // own module and the code that assembles a head's provider may build one. See
    // PASSTHROUGH_QUIRKS_ALLOWED_SITES for the pointer correction — the briefing said
    // "Daemon.kt"; Daemon.kt never constructs one.
    @Test
    fun `PassthroughQuirks is constructed only by its module or head assembly - HD-9`() {
        val files = allProductionFiles(map)
        org.junit.jupiter.api.Assertions.assertTrue(files.size > 10) {
            "the tree yielded ${files.size} production file(s) — the walk is broken, and a law that " +
                "reads no files passes vacuously."
        }
        val allowedPrefixes = passthroughQuirksAllowedPrefixes(map)
        val violations = files.sortedBy { it.path }.flatMap { file ->
            passthroughQuirksConstructionViolations(
                file.relativeTo(map.root).path,
                file.readText(),
                allowedPrefixes,
            )
        }
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "PASSTHROUGH-QUIRKS CONSTRUCTION (HD-9) violated:\n  - ",
            )
        }
    }

    // HD-9: the construction guard proven against SYNTHETIC input — the live tree is clean today,
    // so a silently-deleted matcher would look identical to a passing law.
    @Test
    fun `the PassthroughQuirks construction guard can actually fail - HD-9`() {
        val allowedPrefixes = passthroughQuirksAllowedPrefixes(map)
        // The synthetic paths are spelled THROUGH THE MAP, exactly like the prefixes they are
        // graded against. A proof that hardcodes `app/src/...` silently stops being the case it
        // claims to prove the moment a module's directory changes — which it did when the Gradle
        // root moved to the repository root and every module's directory gained its `gateway/`.
        val ownModule = map.relativeDir(":integrations-dialects-anthropic")
        val headAssembly = map.relativeDir(":app")
        val headModule = map.relativeDir(":features-turns")
        assertEquals(
            emptyList<String>(),
            passthroughQuirksConstructionViolations(
                "$ownModule/src/main/kotlin/splice/dialect/passthrough/PassthroughQuirks.kt",
                "public data class PassthroughQuirks(\n    val providerTag: String,\n)\n",
                allowedPrefixes,
            ),
            "the declaring class line is not a construction, and its own module is allowed anyway",
        )
        assertEquals(
            emptyList<String>(),
            passthroughQuirksConstructionViolations(
                "$headAssembly/src/main/kotlin/splice/app/provider/PassthroughArm.kt",
                "val q = PassthroughQuirks(providerTag = key)\n",
                allowedPrefixes,
            ),
            "head assembly's own provider package is allowed",
        )
        assertEquals(
            listOf(
                "$headModule/src/main/kotlin/splice/head/HeadServer.kt:2 constructs PassthroughQuirks " +
                    "outside its allowed sites (${allowedPrefixes.joinToString()}) — a provider's " +
                    "deformation profile belongs to the module that owns the provider or to head " +
                    "assembly, not to whichever file happens to need it.",
            ),
            passthroughQuirksConstructionViolations(
                "$headModule/src/main/kotlin/splice/head/HeadServer.kt",
                "package splice.head\nval q = PassthroughQuirks(providerTag = \"x\")\n",
                allowedPrefixes,
            ),
            "a construction outside the allowed sites must fail BY NAME, naming the exact line",
        )
    }

    // HD-9 (#924 capstone): dialects speak wire format; they do not read topology/operator config.
    @Test
    fun `no dialect main file imports the topology package - HD-9`() {
        val modules = dialectModules(map)
        org.junit.jupiter.api.Assertions.assertTrue(modules.size >= 3) {
            "found ${modules.size} dialect module(s) — the walk is broken, and a law that reads no " +
                "dialect modules passes vacuously."
        }
        val files = modules.sorted().flatMap { module ->
            map.mainSources(module).walkTopDown().filter { it.isFile && it.extension == "kt" }
        }
        val violations = files.sortedBy { it.path }.flatMap { file ->
            topologyImportViolations(file.relativeTo(map.root).path, file.readText())
        }
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "DIALECT TOPOLOGY IMPORT (HD-9) violated:\n  - ",
            )
        }
    }

    // HD-9: the import guard proven against SYNTHETIC input.
    @Test
    fun `the dialect topology import guard can actually fail - HD-9`() {
        assertEquals(
            emptyList<String>(),
            topologyImportViolations("x/Y.kt", "package x\nimport splice.core.util.LogSink\n"),
            "an unrelated core import is not a topology import",
        )
        assertEquals(
            listOf(
                "x/Y.kt:2 imports splice.core.topology.AuthKind — dialects adapt one wire format and " +
                    "must not read topology/operator config directly; take what you need as a typed " +
                    "parameter instead.",
            ),
            topologyImportViolations("x/Y.kt", "package x\nimport splice.core.topology.AuthKind\n"),
            "a member import must fail BY NAME, naming the exact line",
        )
        assertEquals(
            listOf(
                "x/Y.kt:2 imports splice.core.topology — dialects adapt one wire format and must not " +
                    "read topology/operator config directly; take what you need as a typed parameter " +
                    "instead.",
            ),
            topologyImportViolations("x/Y.kt", "package x\nimport splice.core.topology\n"),
            "the bare package import must fail too",
        )
    }
}
