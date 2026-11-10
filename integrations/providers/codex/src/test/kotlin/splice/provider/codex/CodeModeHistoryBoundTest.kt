package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.InputDigest
import splice.provider.codex.history.CodeModeLedgerEntry
import splice.provider.codex.history.CodeModeUpstreamLedger
import splice.provider.codex.history.durable.CodeModeHistoryStorage
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** A long conversation keeps working past its journal's bound, and its journal survives what happens to it on disk. */
class CodeModeHistoryBoundTest : CodeModeBridgeTestSupport() {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `a conversation keeps taking turns past its journal's bound and its newest history survives a restart`() {
        val ledger = ledger()
        var last = CodeModeLedgerEntry(emptyList(), emptyList(), emptyList())
        repeat(TURNS) { turn ->
            // Each turn shares nothing with the last, as after a compaction, so the journal grows by a whole post.
            val covered = listOf(user("turn-$turn-" + "c".repeat(POST_CHARS)))
            last = CodeModeLedgerEntry(covered, covered + user("sent-$turn"), emptyList())
            ledger.record("long", last)
        }
        assertTrue(Files.size(journal("long")) <= BOUND_BYTES, "the journal outgrew its bound")
        val restored = ledger()
        assertEquals(last.sent, restored.entries("long").first().sent)
        assertEquals(TURNS.toLong(), restored.newest("long"))
        restored.output("long", TURNS.toLong(), listOf(user("answer")), emptyList())
        assertEquals(last.sent + user("answer"), ledger().entries("long").first().sent)
    }

    @Test
    fun `a journal removed under a running head is written whole at its next turn`() {
        val ledger = ledger()
        val first = listOf(user("begin"))
        ledger.record("key", CodeModeLedgerEntry(first, first, emptyList()))
        ledger.record("key", CodeModeLedgerEntry(first + user("two"), first + user("two"), emptyList()))
        Files.delete(journal("key"))
        val third = first + user("two") + user("three")
        ledger.record("key", CodeModeLedgerEntry(third, third, emptyList()))
        val restored = ledger().entries("key")
        assertEquals(third, restored.first().sent)
        assertEquals(3L, restored.first().id)
    }

    @Test
    fun `history kept while journaling was off is written whole once it is back`() {
        var journaling = false
        val location = stateLocation().copy(journaling = CodeModeJournaling { journaling })
        val ledger = CodeModeUpstreamLedger(storage = CodeModeHistoryStorage(location, json, BOUND_BYTES))
        val first = listOf(user("begin"))
        ledger.record("key", CodeModeLedgerEntry(first, first, emptyList()))
        ledger.record("key", CodeModeLedgerEntry(first + user("two"), first + user("two"), emptyList()))
        assertFalse(Files.exists(journal("key")))
        journaling = true
        val third = first + user("two") + user("three")
        ledger.record("key", CodeModeLedgerEntry(third, third, emptyList()))
        assertEquals(third, ledger().entries("key").first().sent)
    }

    @Test
    fun `a conversation kept only in memory keeps its newest history within the memory budget`() {
        val location = stateLocation().copy(journaling = CodeModeJournaling { false })
        val ledger = CodeModeUpstreamLedger(
            storage = CodeModeHistoryStorage(location, json, BOUND_BYTES),
            cacheBytes = BOUND_BYTES,
        )
        var last = emptyList<JsonElement>()
        repeat(TURNS) { turn ->
            last = listOf(user("turn-$turn-" + "m".repeat(POST_CHARS)))
            ledger.record("memory", CodeModeLedgerEntry(last, last, emptyList()))
        }
        val posts = ledger.entries("memory")
        assertEquals(last, posts.first().sent)
        assertTrue(posts.size < TURNS, "every post of a memory-only conversation was kept")
    }

    @Test
    fun `the sweep removes journals unused past the cut and leaves the ones a head holds`() {
        val ledger = ledger()
        ledger.record("cold", CodeModeLedgerEntry(listOf(user("cold")), listOf(user("cold")), emptyList()))
        ledger.record("hot", CodeModeLedgerEntry(listOf(user("hot")), listOf(user("hot")), emptyList()))
        val old = FileTime.fromMillis(OLD_MS)
        Files.setLastModifiedTime(journal("cold"), old)
        Files.setLastModifiedTime(journal("hot"), old)
        val swept = CodeModeHistoryStorage(stateLocation(), json, BOUND_BYTES).sweep(CUT_MS, setOf("hot"))
        assertEquals(1, swept.journals)
        assertFalse(Files.exists(journal("cold")))
        assertFalse(Files.exists(journal("cold").resolveSibling("${journal("cold").fileName}.lock")))
        assertEquals(listOf(user("hot")), ledger().entries("hot").first().sent)
        assertTrue(ledger().entries("cold").isEmpty())
    }

    private fun ledger() = CodeModeUpstreamLedger(storage = CodeModeHistoryStorage(stateLocation(), json, BOUND_BYTES))

    private fun journal(key: String) = stateLocation().dir.resolve("history").resolve(InputDigest.hex(key) + ".jsonl")

    private fun user(text: String): JsonElement = buildJsonObject {
        put("role", "user")
        put("content", JsonPrimitive(text))
    }
}

private const val BOUND_BYTES = 64L * 1024
private const val POST_CHARS = 4_000
private const val TURNS = 60
private const val OLD_MS = 1_000L
private const val CUT_MS = 2_000L
