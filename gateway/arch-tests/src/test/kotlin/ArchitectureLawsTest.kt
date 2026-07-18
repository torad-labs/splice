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

    private companion object {
        const val SLOT_HEADER_LAW =
            "Slot-authoring law (#963): first line must be '// PORT-OF: <source> @ <sha> — invariants: ...' " +
                "for ported code or '// NEW: <reason>' for new code. The declaration is the survival artifact."
    }
}
