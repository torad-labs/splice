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
    "provider-codex", "provider-grok", "provider-openai", "gateway", "control", "app",
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

    private companion object {
        const val SLOT_HEADER_LAW =
            "Slot-authoring law (#963): first line must be '// PORT-OF: <source> @ <sha> — invariants: ...' " +
                "for ported code or '// NEW: <reason>' for new code. The declaration is the survival artifact."
    }
}
