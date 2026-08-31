// NEW: Konsist architecture laws (P1-KONSIST) — ring 3 of the enforcement stack.
// These arm as code lands: an empty scope passes vacuously, a violation fails :arch-tests:test.
// Grow this file as modules land; every new law gets a red/green proof in the ledger note.
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Production modules whose every .kt file must open with a slot header (#963):
 *  `// PORT-OF: <source> @ <sha> — invariants: ...` or `// NEW: <reason>`. */
private val PORT_SCOPE_MODULES = listOf(
    "core", "provider-spi", "dialect-openai-responses", "dialect-openai-chat",
    "dialect-anthropic-passthrough", "provider-codex", "provider-grok", "provider-openai",
    "provider-kimi", "gateway", "control", "app",
)

/** Every dialect module — what :gateway is allowed to know about, and what a provider picks from. */
private val DIALECTS = setOf(
    ":dialect-anthropic-passthrough",
    ":dialect-openai-responses",
    ":dialect-openai-chat",
)

/** The domain plus the provider contract: what every adapter (dialect, provider, transport) starts from. */
private val ADAPTER_BASE = setOf(":core", ":provider-spi")

/** Ports-and-adapters dependency direction (HD-11): module -> the internal modules it may depend on,
 *  in ANY Gradle configuration — main OR test.
 *
 *  The MAIN-source half of this shape is already a build error at configuration time
 *  (build-logic/src/main/kotlin/splice.module-law.gradle.kts). That plugin deliberately exempts test
 *  configurations — "integration tests legitimately wire sibling modules" — but the exemption is
 *  blanket, and every inverted edge this repo actually has lives inside it. This law is the
 *  finer-grained test-plane half: :gateway tests reaching a DIALECT is legitimate and stays legal
 *  here, while a provider reaching back into :gateway is an inversion whether the wire is in main
 *  or in test. The two laws are read together; neither subsumes the other.
 *
 *  Edges come from the build files, not konsist's package model: these are GRADLE-module edges, and
 *  a module can depend on another before any file imports it.
 *
 *  Modules absent from this map are unrestricted — see UNRESTRICTED_MODULES; a module that is in
 *  neither is a failure, because silent absence is how a rule set stops applying. */
private val MODULE_DEPENDENCY_LAW: Map<String, Set<String>> = mapOf(
    // the domain. Depends on nothing internal, forever.
    ":core" to emptySet(),
    // the provider contract. Speaks the domain and nothing else.
    ":provider-spi" to setOf(":core"),
    // a dialect adapts the contract to one wire format.
    ":dialect-anthropic-passthrough" to ADAPTER_BASE,
    ":dialect-openai-responses" to ADAPTER_BASE,
    ":dialect-openai-chat" to ADAPTER_BASE,
    // a provider speaks its own dialect(s) — never another provider, never the transport.
    ":provider-codex" to ADAPTER_BASE + ":dialect-openai-responses",
    ":provider-grok" to ADAPTER_BASE + ":dialect-openai-responses",
    ":provider-kimi" to ADAPTER_BASE + ":dialect-anthropic-passthrough",
    ":provider-openai" to ADAPTER_BASE + setOf(":dialect-openai-responses", ":dialect-openai-chat"),
    // the transport serves any dialect; it must not know a CONCRETE provider (that is :app's job).
    ":gateway" to ADAPTER_BASE + DIALECTS,
    // the management plane reads the domain only.
    ":control" to setOf(":core"),
)

/** Exempt from the direction law: :app is the composition root and may wire anything, and the rest are
 *  harnesses rather than product layers — the same set splice.module-law.gradle.kts calls `nonLibrary`. */
private val UNRESTRICTED_MODULES = setOf(":app", ":spikes", ":arch-tests", ":fir-checks")

/** RATCHET ALLOWLIST — the inverted edges that already existed when this law landed, every one of them
 *  test-configuration only. Each line is DEBT, not permission: the law passes today, every NEW
 *  inversion fails immediately, and this map is the visible worklist. Delete a line when the edge goes
 *  — a listed edge that no longer exists FAILS the law, so the list cannot rot into blanket permission. */
private val DEPENDENCY_RATCHET: Map<Pair<String, String>, String> = mapOf(
    (":provider-grok" to ":gateway") to
        "pre-existing, 2026-08-16, tracked for removal — grok's tests drive a real gateway server " +
        "(testImplementation + testFixtures); the harness belongs somewhere both can depend on.",
    (":provider-openai" to ":gateway") to
        "pre-existing, 2026-08-16, tracked for removal — same shape and same fix as provider-grok.",
    (":gateway" to ":provider-codex") to
        "pre-existing, 2026-08-16, tracked for removal — gateway's tests construct a CONCRETE " +
        "provider (testImplementation); the seam should be a provider-spi fake.",
)

class ArchitectureLawsTest {

    private val root: File = File(System.getProperty("gateway.root"))

    // Konsist resolves scopeFromDirectory RELATIVE to the Gradle root it detects;
    // absolute paths get prefixed and blow up (caught in this law's first red/green).
    private fun mainScope(module: String) =
        Konsist.scopeFromDirectory("$module/src/main/kotlin")

    @Test
    fun `slot headers - every production file declares PORT-OF or NEW`() {
        PORT_SCOPE_MODULES.forEach { module ->
            val dir = File(root, "$module/src/main/kotlin")
            if (!dir.exists()) return@forEach
            mainScope(module).files.assertTrue(additionalMessage = SLOT_HEADER_LAW) { file ->
                val firstLine = file.text.lineSequence().firstOrNull().orEmpty()
                firstLine.startsWith("// PORT-OF: ") || firstLine.startsWith("// NEW: ")
            }
        }
    }

    @Test
    fun `core stays framework-free - no ktor imports in core`() {
        val dir = File(root, "core/src/main/kotlin")
        if (!dir.exists()) return
        mainScope("core").imports.assertTrue { !it.name.startsWith("io.ktor") }
    }

    @Test
    fun `core wire types are serializable`() {
        val dir = File(root, "core/src/main/kotlin")
        if (!dir.exists()) return
        mainScope("core")
            .classes()
            .filter { it.resideInPackage("..wire..") }
            .assertTrue { cls -> cls.annotations.any { it.name.endsWith("Serializable") } }
    }

    // C3 coverage-by-law (#924 Phase 1): the request-byte contract is not opt-in. Every module that
    // ships a *RequestBuilder must also ship at least one contract/<name>.json golden — so a new
    // dialect arrives WITH its exact-request-bytes fixture (the stream_options / gzip incident class
    // becomes a failing unit test) rather than un-pinned. The receipt-binding half (a changed golden
    // must match a live-200 receipt) activates on traffic; see gateway/CONTRACT.md.
    @Test
    fun `every RequestBuilder module ships a request-byte contract fixture`() {
        val builderModules = PORT_SCOPE_MODULES.filter { module ->
            val mainDir = File(root, "$module/src/main/kotlin")
            mainDir.isDirectory && mainDir.walkTopDown().any { it.isFile && it.name.endsWith("RequestBuilder.kt") }
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            builderModules.isNotEmpty(),
            "expected at least one *RequestBuilder module — did the scope list or module layout change?",
        )
        val missing = builderModules.filter { module ->
            val contractDir = File(root, "$module/src/test/resources/contract")
            !contractDir.isDirectory || contractDir.listFiles { f -> f.extension == "json" }.isNullOrEmpty()
        }
        org.junit.jupiter.api.Assertions.assertTrue(missing.isEmpty()) {
            "RequestBuilder modules missing a request-byte contract fixture (#924 Phase 1): $missing — " +
                "add src/test/resources/contract/<name>.json + a *ContractTest. See gateway/CONTRACT.md."
        }
    }

    // HD-11: the dormant-rule repair, module-graph half. A rule set that nothing routes reports zero
    // findings forever (.rules/kotlin did, for a month) — and so does an architecture diagram that
    // lives only in a doc. This makes the direction executable, and its exceptions countable.
    @Test
    fun `module dependency direction - ports and adapters, with a dated ratchet`() {
        val modules = includedModules()
        org.junit.jupiter.api.Assertions.assertTrue(modules.size > 1) {
            "settings.gradle.kts yielded ${modules.size} modules — the include() parse is broken, " +
                "and a law that reads no modules passes vacuously."
        }
        val violations = mutableListOf<String>()

        // Every included module is either governed or explicitly unrestricted. A module in neither is
        // silently exempt, which is the same fail-open as an unrouted rule directory.
        (modules - MODULE_DEPENDENCY_LAW.keys - UNRESTRICTED_MODULES).forEach { module ->
            violations += "$module is in settings.gradle.kts but in neither MODULE_DEPENDENCY_LAW " +
                "nor UNRESTRICTED_MODULES — add it to the law (preferred) or justify it as a harness."
        }
        // ...and the law must not name modules that do not exist: a typo'd key enforces nothing.
        (MODULE_DEPENDENCY_LAW.keys - modules).forEach { module ->
            violations += "MODULE_DEPENDENCY_LAW names $module, which settings.gradle.kts does not " +
                "include — fix the key or drop it; it currently governs nothing."
        }

        val actualEdges = modules.flatMap { module ->
            projectDependencies(module).map { dep -> module to dep }
        }.toSet()
        modules.forEach { module ->
            val allowed = MODULE_DEPENDENCY_LAW[module] ?: return@forEach
            projectDependencies(module).forEach { dep ->
                if (dep in allowed) return@forEach
                if ((module to dep) in DEPENDENCY_RATCHET) return@forEach
                violations += "$module may not depend on $dep (allowed: ${allowed.sorted()}). " +
                    "The direction is the architecture. If the edge is deliberate and temporary, add " +
                    "'\"$module\" to \"$dep\"' to DEPENDENCY_RATCHET in this file with a dated reason; " +
                    "otherwise invert it (depend on the port, not the layer above)."
            }
        }
        // A ratchet only ratchets if paying the debt is what removes the line.
        DEPENDENCY_RATCHET.keys.filterNot { it in actualEdges }.forEach { edge ->
            violations += "DEPENDENCY_RATCHET still lists ${edge.first} -> ${edge.second}, which no " +
                "longer exists — delete the entry so the list keeps meaning 'known debt'."
        }

        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "MODULE DEPENDENCY DIRECTION (HD-11) violated:\n  - ",
            )
        }
    }

    /** Module paths from settings.gradle.kts. Every quoted `:name` in that file is an include() entry —
     *  rootProject.name and includeBuild("build-logic") carry no leading colon. */
    private fun includedModules(): Set<String> =
        MODULE_PATH.findAll(stripComments(File(root, "settings.gradle.kts").readText()))
            .map { it.groupValues[1] }
            .toSet()

    /** Project-dependency edges declared by a module's build file, in EVERY configuration —
     *  implementation, api, testImplementation, testFixtures(...) and the rest. */
    private fun projectDependencies(module: String): Set<String> {
        val buildFile = File(root, "${module.removePrefix(":")}/build.gradle.kts")
        if (!buildFile.isFile) return emptySet()
        return PROJECT_DEPENDENCY.findAll(stripComments(buildFile.readText()))
            .map { it.groupValues[1] }
            .filterNot { it == module }
            .toSet()
    }

    /** Block and line comments out: a commented-out dependency is not an edge. */
    private fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        val MODULE_PATH = Regex("\"(:[A-Za-z0-9._-]+)\"")

        // DR-112: match every Gradle spelling of a project edge — positional `project(":x")`, the
        // named-arg form `project(path = ":x")`, whitespace variants, and a trailing
        // `, configuration = ...` — not just the exact positional idiom. An edge written any other
        // way was invisible to the direction law and the ratchet-staleness check alike.
        val PROJECT_DEPENDENCY = Regex("""project\(\s*(?:path\s*=\s*)?"(:[A-Za-z0-9._-]+)"""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\n]*")

        const val SLOT_HEADER_LAW =
            "Slot-authoring law (#963): first line must be '// PORT-OF: <source> @ <sha> — invariants: ...' " +
                "for ported code or '// NEW: <reason>' for new code. The declaration is the survival artifact."
    }
}
