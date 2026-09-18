// NEW: Konsist architecture laws (P1-KONSIST) — ring 3 of the enforcement stack.
// These arm as code lands: an empty scope passes vacuously, a violation fails :arch-tests:test.
// Grow this file as modules land; every new law gets a red/green proof in the ledger note.
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** Production modules whose every .kt file must open with a slot header (#963):
 *  `// PORT-OF: <source> @ <sha> — invariants: ...` or `// NEW: <reason>`.
 *
 *  DR-165: this list is no longer the DENOMINATOR, only the covered set. It used to be both, and a
 *  denominator taken from the list being checked cannot fail for anything absent from that list —
 *  the same tautology the module-direction law below already avoids by reading settings.gradle.kts.
 *  `fir-checks` was the live proof: it ships three production files and was simply not here, so
 *  neither this law nor the contract-coverage law had ever looked at it. */
private val PORT_SCOPE_MODULES = listOf(
    "core", "provider-spi", "dialect-openai-responses", "dialect-openai-chat",
    "dialect-anthropic-passthrough", "provider-codex", "provider-grok", "provider-openai",
    "provider-kimi", "provider-muse", "gateway", "control", "app", "fir-checks",
)

/** DR-165: modules that ship production Kotlin and are deliberately OUT of the slot-header law,
 *  each with a written reason. Empty today, and that is the honest state — `fir-checks` is the only
 *  harness with production sources and its files already carried slot headers, so it is covered
 *  above rather than excused here. The mechanism exists so a future exclusion has somewhere to go
 *  that carries a REASON: a module in neither map fails by name, and a blank reason fails too,
 *  because a placeholder is an absence wearing a label. */
private val SLOT_HEADER_EXEMPT: Map<String, String> = emptyMap()

/** DR-165: every module that ACTUALLY ships production Kotlin, read off the tree. This is the
 *  denominator both coverage laws now use — the module-direction law already derives its own from
 *  settings.gradle.kts, and these two were the stragglers still trusting a hand-authored list. */
private fun productionModules(root: File): Set<String> =
    root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .filter { module ->
            val main = File(module, "src/main/kotlin")
            main.isDirectory && main.walkTopDown().any { it.isFile && it.extension == "kt" }
        }
        .map { it.name }
        .toSet()

/** DR-165: the disposition of every production module, as violation lines. PURE so it can be proven
 *  against synthetic input — with the live tree fully dispositioned, nothing real can red it, which
 *  is exactly the unfalsifiable shape this row exists to remove. */
private fun slotHeaderDispositions(
    onDisk: Set<String>,
    covered: Set<String>,
    exempt: Map<String, String>,
): List<String> {
    val violations = mutableListOf<String>()
    (onDisk - covered - exempt.keys).sorted().forEach { module ->
        violations += "$module ships production Kotlin but is in neither PORT_SCOPE_MODULES nor " +
            "SLOT_HEADER_EXEMPT — cover it (preferred) or exempt it WITH a written reason."
    }
    exempt.filterValues { it.isBlank() }.keys.sorted().forEach { module ->
        violations += "$module is exempted from the slot-header law with a blank reason — a placeholder " +
            "is an absence wearing a label; write why, or cover the module."
    }
    (covered - onDisk).sorted().forEach { module ->
        violations += "PORT_SCOPE_MODULES names $module, which ships no production Kotlin — drop the " +
            "entry; it currently governs nothing."
    }
    (exempt.keys - onDisk).sorted().forEach { module ->
        violations += "SLOT_HEADER_EXEMPT names $module, which ships no production Kotlin — drop the " +
            "entry; it currently exempts nothing."
    }
    return violations
}

/** DR-165: one *RequestBuilder module's contract verdict, or null when it is fully covered. PURE for
 *  the same reason as [slotHeaderDispositions]: every live builder module already ships both halves,
 *  so the tree cannot red the CONSUMER half, and an addition the tree cannot falsify would be the
 *  exact defect this row repairs. The fixture below is what makes it real. */
private fun contractViolation(module: String, hasFixture: Boolean, hasConsumer: Boolean): String? = when {
    !hasFixture -> "$module ships a *RequestBuilder but no src/test/resources/contract/<name>.json"
    !hasConsumer ->
        "$module has a contract fixture but no *ContractTest.kt reading it — a golden " +
            "nothing compares against pins nothing"
    else -> null
}

class ArchitectureLawsTest {

    private val root: File = File(System.getProperty("gateway.root"))

    // Konsist resolves scopeFromDirectory RELATIVE to the Gradle root it detects;
    // absolute paths get prefixed and blow up (caught in this law's first red/green).
    private fun mainScope(module: String) =
        Konsist.scopeFromDirectory("$module/src/main/kotlin")

    // DR-165 (found by codex-splice's test audit, confirmed by a mutant that deleted a whole dialect
    // from PORT_SCOPE_MODULES while the suite stayed green): the denominator now comes from the
    // SOURCE TREE, and every module that ships production Kotlin must carry a DISPOSITION —
    // covered, or exempt with a written reason. Absence is not a disposition, so a module in
    // neither fails BY NAME instead of silently leaving coverage.
    @Test
    fun `slot headers - every production module is dispositioned, and its files declare PORT-OF or NEW`() {
        val onDisk = productionModules(root)
        org.junit.jupiter.api.Assertions.assertTrue(onDisk.size > 1) {
            "the source tree yielded ${onDisk.size} production modules — the walk is broken, " +
                "and a law that reads no modules passes vacuously."
        }
        val violations = slotHeaderDispositions(onDisk, PORT_SCOPE_MODULES.toSet(), SLOT_HEADER_EXEMPT)
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "SLOT-HEADER COVERAGE (DR-165) violated:\n  - ",
            )
        }
        (onDisk intersect PORT_SCOPE_MODULES.toSet()).forEach { module ->
            mainScope(module).files.assertTrue(additionalMessage = SLOT_HEADER_LAW) { file ->
                val firstLine = file.text.lineSequence().firstOrNull().orEmpty()
                firstLine.startsWith("// PORT-OF: ") || firstLine.startsWith("// NEW: ")
            }
        }
    }

    // DR-165: the disposition logic proven against SYNTHETIC input, because the live tree cannot
    // falsify it — with every module dispositioned, deleting the check entirely would still pass.
    // That is precisely the tautology this row exists to remove, so the guard gets its own fixture,
    // exactly as DR-112 did for the edge matcher. The boring cases are the ones that get waved
    // through, so they are all here: an unlisted module, a stale listing, and a blank reason.
    @Test
    fun `the disposition guard can actually fail - DR-165`() {
        assertEquals(
            emptyList<String>(),
            slotHeaderDispositions(setOf("core", "harness"), setOf("core"), mapOf("harness" to "a reason")),
            "covered plus exempt-with-a-reason is a complete disposition",
        )
        assertEquals(
            listOf(
                "newmod ships production Kotlin but is in neither PORT_SCOPE_MODULES nor " +
                    "SLOT_HEADER_EXEMPT — cover it (preferred) or exempt it WITH a written reason.",
            ),
            slotHeaderDispositions(setOf("core", "newmod"), setOf("core"), emptyMap()),
            "a module the source tree has and no list mentions must fail BY NAME",
        )
        assertEquals(
            listOf(
                "harness is exempted from the slot-header law with a blank reason — a placeholder " +
                    "is an absence wearing a label; write why, or cover the module.",
            ),
            slotHeaderDispositions(setOf("core", "harness"), setOf("core"), mapOf("harness" to "  ")),
            "a blank reason is not a disposition",
        )
        assertEquals(
            listOf(
                "PORT_SCOPE_MODULES names gone, which ships no production Kotlin — drop the entry; " +
                    "it currently governs nothing.",
            ),
            slotHeaderDispositions(setOf("core"), setOf("core", "gone"), emptyMap()),
            "a listing that governs nothing must fail, or the list rots into decoration",
        )
    }

    // DR-165: the CONSUMER half of the contract law, proven against synthetic input for the same
    // reason. Every live *RequestBuilder module already ships both a fixture and a *ContractTest, so
    // nothing in the tree can red it — and a guard the tree cannot falsify is the shape this row
    // exists to remove, not one it may quietly add.
    @Test
    fun `the contract-coverage guard can actually fail - DR-165`() {
        assertEquals(null, contractViolation("dialect-x", hasFixture = true, hasConsumer = true))
        assertEquals(
            "dialect-x ships a *RequestBuilder but no src/test/resources/contract/<name>.json",
            contractViolation("dialect-x", hasFixture = false, hasConsumer = true),
            "a builder with no fixture is the #924 Phase 1 case",
        )
        assertEquals(
            "dialect-x has a contract fixture but no *ContractTest.kt reading it — a golden " +
                "nothing compares against pins nothing",
            contractViolation("dialect-x", hasFixture = true, hasConsumer = false),
            "a fixture with no consumer is the fail-open one layer down",
        )
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
    // DR-165: the builder modules come from the SOURCE TREE, not from PORT_SCOPE_MODULES. Derived
    // from the allowlist, this law could be switched off for a whole dialect by deleting one string
    // — codex-splice's mutant removed dialect-openai-responses and the suite stayed green 16/16,
    // so that module's request bytes went unpinned while the law still reported coverage.
    //
    // It also now requires a live CONSUMER, not only a fixture: a contract/<name>.json that no
    // *ContractTest reads is a golden nothing compares against — the same fail-open one layer down,
    // and the reason a fixture alone was never the guarantee this law claims to give.
    @Test
    fun `every RequestBuilder module ships a request-byte contract fixture and a test that reads it`() {
        val builderModules = productionModules(root).filter { module ->
            val mainDir = File(root, "$module/src/main/kotlin")
            mainDir.isDirectory && mainDir.walkTopDown().any { it.isFile && it.name.endsWith("RequestBuilder.kt") }
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            builderModules.isNotEmpty(),
            "expected at least one *RequestBuilder module — did the module layout change?",
        )
        val violations = builderModules.mapNotNull { module ->
            val contractDir = File(root, "$module/src/test/resources/contract")
            val hasFixture =
                contractDir.isDirectory && !contractDir.listFiles { f -> f.extension == "json" }.isNullOrEmpty()
            val testDir = File(root, "$module/src/test/kotlin")
            val hasConsumer = testDir.isDirectory &&
                testDir.walkTopDown().any { it.isFile && it.name.endsWith("ContractTest.kt") }
            contractViolation(module, hasFixture, hasConsumer)
        }
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "REQUEST-BYTE CONTRACT COVERAGE (#924 Phase 1, DR-165) violated:\n  - ",
                postfix = "\nSee gateway/CONTRACT.md.",
            )
        }
    }

    private companion object {
        const val SLOT_HEADER_LAW =
            "Slot-authoring law (#963): first line must be '// PORT-OF: <source> @ <sha> — invariants: ...' " +
                "for ported code or '// NEW: <reason>' for new code. The declaration is the survival artifact."
    }
}
