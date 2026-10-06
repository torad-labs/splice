// NEW: journal restoration budgets each entry, the complete checkpoint and the escaped live state.
package splice.provider.codex.state

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecords
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CodeModeRestoreBudgetTest(@param:TempDir private val dir: Path) {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `an oversized committed entry is refused without changing durable evidence`() {
        val record = CodeModeRecords.of("synthetic", 1).snapshot()
        val file = dir.resolve("oversized.jsonl")
        val checkpoint = CodeModePersistedState(records = listOf(record))
        val next = record.copy(output = "x".repeat(2 * 1024 * 1024))
        val delta = CodeModeStateDelta(record.key, listOf(next), emptySet(), emptyList())
        Files.writeString(file, json.encodeToString(checkpoint) + "\n" + json.encodeToString(delta) + "\n")
        val before = hash(file)
        val heap = HeapBudget(Long.MAX_VALUE, 128 * 1024)
        assertThrows<HeapCapacityException> { CodeModeStateJournal.read(file, json, heap) }
        assertEquals(before, hash(file))
    }

    @Test
    fun `a pretty checkpoint is bounded as a whole even when each source line fits`() {
        val records = List(128) { CodeModeRecords.of("synthetic", it).snapshot() }
        val pretty = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val text = pretty.encodeToString(CodeModePersistedState(records = records))
        assertTrue(text.lineSequence().all { it.length < 1024 })
        val file = dir.resolve("pretty.json")
        Files.writeString(file, text + "\n")
        val before = hash(file)
        val heap = HeapBudget(Long.MAX_VALUE, 128 * 1024)
        assertThrows<HeapCapacityException> { CodeModeStateJournal.read(file, pretty, heap) }
        assertEquals(before, hash(file))
    }

    @Test
    fun `a journal larger than the ledger restores only its current live graph`() {
        val record = CodeModeRecords.of("synthetic", 1).snapshot()
        val file = dir.resolve("many.jsonl")
        Files.newBufferedWriter(file).use { writer ->
            writer.appendLine(json.encodeToString(CodeModePersistedState(records = listOf(record))))
            repeat(1000) { index ->
                val delta = CodeModeStateDelta(
                    record.key,
                    listOf(record.copy(output = "next-$index")),
                    emptySet(),
                    emptyList(),
                )
                writer.appendLine(json.encodeToString(delta))
            }
        }
        val heap = HeapBudget(Long.MAX_VALUE, 512 * 1024)
        assertTrue(Files.size(file) > heap.limitBytes)
        val restored = CodeModeStateJournal.read(file, json, heap)
        assertEquals("next-999", restored.records.single().output)
        assertTrue(heap.available.value < heap.limitBytes, "returned state must retain its charge")
    }

    @Test
    fun `returned snapshot payloads remain charged after their decoding stages close`() {
        val record = CodeModeRecords.of("synthetic", 1).snapshot().copy(output = "x".repeat(16 * 1024))
        val file = dir.resolve("escaped.jsonl")
        Files.writeString(file, json.encodeToString(CodeModePersistedState(records = listOf(record))) + "\n")
        val heap = HeapBudget(Long.MAX_VALUE, 2 * 1024 * 1024)
        val restored = CodeModeStateJournal.read(file, json, heap)
        assertEquals(record.output, restored.records.single().output)
        assertTrue(heap.available.value <= heap.limitBytes - checkNotNull(record.output).length * 2L)
    }

    @Test
    fun `native following witnesses remain charged on an owned live record`() {
        val record = anchoredRecord()
        val heap = HeapBudget(Long.MAX_VALUE, 4 * 1024 * 1024)

        CodeModeHeap.own(record, heap)

        assertTrue(
            heap.limitBytes - heap.available.value >= 2048L * 64L,
            "the digests alone exceed the fixed metadata allowance and must remain charged",
        )
    }

    @Test
    fun `native following witnesses remain charged after journal decode stages close`() {
        val record = anchoredRecord().snapshot()
        val file = dir.resolve("anchored.jsonl")
        Files.writeString(file, json.encodeToString(CodeModePersistedState(records = listOf(record))) + "\n")
        val heap = HeapBudget(Long.MAX_VALUE, 16 * 1024 * 1024)

        val restored = CodeModeStateJournal.read(file, json, heap)

        assertEquals(2048, restored.records.single().replayAnchors?.nativeFollowing?.size)
        assertTrue(
            heap.limitBytes - heap.available.value >= 2048L * 64L,
            "escaped placement metadata must not be refunded with the raw decoding trees",
        )
    }

    private fun anchoredRecord(): CodeModeRecord = CodeModeRecords.of("synthetic", 1).apply {
        replayAnchors = CodeModeReplayAnchors(
            CodeModeHistoryAnchor(null, 0),
            emptyMap(),
            (0 until 2048).associateWith { CodeModeHistoryAnchor(it.toString().padStart(64, '0'), 0) },
        )
    }

    private fun hash(file: Path): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)),
    )
}
