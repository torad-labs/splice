package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.provider.codex.history.CodeModeClientDelta
import splice.provider.codex.history.CodeModeHistoryStorage
import splice.provider.codex.history.CodeModeLedgerEntry
import splice.provider.codex.history.CodeModeUpstreamLedger
import java.nio.file.Files

class CodeModeDurableAuthorityTest : CodeModeBridgeTestSupport() {
    private val json = Json { encodeDefaults = true }
    private val codec = CodexCodeModeHistoryCodec(json)

    @Test
    fun `cache eviction and restart preserve the exact model items of every conversation and side branch`() {
        val ledger = ledger()
        val native = item("""{"type":"reasoning","id":"rs-hidden","encrypted_content":"private","summary":[]}""")
        for (conversation in 0..40) {
            for (branch in 0..7) {
                val covered = listOf(user("conversation-$conversation"), user("branch-$branch"))
                ledger.record(
                    "conversation-$conversation",
                    CodeModeLedgerEntry(covered, listOf(native) + covered, emptyList()),
                )
            }
        }
        val restored = ledger()
        for (conversation in 0..40) {
            val posts = restored.entries("conversation-$conversation")
            assertEquals(
                (7 downTo 0).map { branch ->
                    listOf(
                        native,
                        user("conversation-$conversation"),
                        user("branch-$branch"),
                    )
                },
                posts.map {
                    it.sent
                },
            )
            assertEquals(8L, restored.newest("conversation-$conversation"))
        }
    }

    @Test
    fun `a response keeps native boundaries while acknowledging merged display blocks and hidden reasoning`() {
        val ledger = ledger()
        val covered = listOf(user("begin"))
        val native = item("""{"type":"reasoning","id":"rs-output","summary":[],"encrypted_content":"private"}""")
        val first = assistant("Same words. ")
        val second = assistant("Same words.")
        ledger.record("key", CodeModeLedgerEntry(covered, covered, emptyList()))
        ledger.output("key", 1L, listOf(native, first, second), listOf(first, second))
        val entry = ledger().entries("key").single()
        val client = covered + assistant("Same words. Same words.") + user("next")
        assertEquals(listOf(user("next")), CodeModeClientDelta(codec).after(entry, client))
        assertEquals(covered + listOf(native, first, second), entry.sent)
    }

    @Test
    fun `a corrupted authority fails closed instead of rebuilding from client prose`() {
        ledger().record("key", CodeModeLedgerEntry(listOf(user("begin")), listOf(user("begin")), emptyList()))
        val file = Files.list(stateLocation().dir.resolve("history")).use { it.findFirst().orElseThrow() }
        Files.writeString(file, "{broken")
        assertThrows(CodeModePersistenceException::class.java) { ledger().entries("key") }
    }

    @Test
    fun `a failed bounded save leaves the previously committed authority recoverable`() {
        val committed = CodeModeLedgerEntry(listOf(user("begin")), listOf(user("begin")), emptyList())
        ledger().record("key", committed)
        val bounded = CodeModeUpstreamLedger(storage = CodeModeHistoryStorage(stateLocation(), json, 1024))
        assertThrows(CodeModePersistenceException::class.java) {
            bounded.record(
                "key",
                CodeModeLedgerEntry(listOf(user("more")), listOf(user("x".repeat(4000))), emptyList()),
            )
        }
        assertEquals(committed.sent, ledger().entries("key").single().sent)
    }

    private fun ledger() = CodeModeUpstreamLedger(
        storage = CodeModeHistoryStorage(stateLocation(), json, 4L * 1024 * 1024),
    )
    private fun user(text: String): JsonElement = item("""{"role":"user","content":"$text"}""")
    private fun assistant(text: String): JsonElement = item("""{"role":"assistant","content":"$text"}""")
    private fun item(text: String): JsonElement = json.parseToJsonElement(text)
}
