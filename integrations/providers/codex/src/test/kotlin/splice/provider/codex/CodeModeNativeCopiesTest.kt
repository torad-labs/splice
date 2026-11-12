// NEW: a journal whose records stored one native item many times at a slot loads with it once and is rewritten.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.provider.codex.state.CodeModeHistoryAnchor
import splice.provider.codex.state.CodeModeJournalEncoding
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.provider.codex.stream.CodeModeSourceState
import java.nio.file.Files
import java.nio.file.Path

// Past the journal's 8 MiB compaction floor with the copies, far under it without them.
private const val COPIES = 150
private const val ENCRYPTED_CHARS = 64 * 1024
private const val MAX_REWRITTEN_BYTES = 512L * 1024

/**
 * Oct 4: one claudex conversation's journal held 38.7 MB of live cells, 18,140 native items with 19 distinct
 * in its last root, stored before the rewrite posted each item once. Its live bytes were the file's own size,
 * so the 4x rule never rewrote it, and retention keeps a conversation in use.
 */
internal class CodeModeNativeCopiesTest : CodeModeBridgeTestSupport() {
    private val big = reasoning("rs-big", "e".repeat(ENCRYPTED_CHARS))
    private val small = reasoning("rs-small", "s")

    @ParameterizedTest(name = "patched after its checkpoint: {0}")
    @ValueSource(booleans = [false, true])
    fun `a record that stored one item many times at a slot loads with it once and its journal is rewritten`(
        patched: Boolean,
    ) {
        val copied = record(
            listOf(
                CodeModeNativeSegment(1, List(COPIES) { listOf(big, small) }.flatten()),
                CodeModeNativeSegment(1, listOf(small)),
                // The same bytes at another slot are history of their own, and stay.
                CodeModeNativeSegment(3, listOf(big)),
            ),
        )
        val store = store().also { it.load() }
        store.save(listOf(copied), emptyList())
        if (patched) {
            copied.progress.output = "again"
            store.save(listOf(copied), emptyList(), dirtyKeys = setOf(copied.key), changedRecord = copied)
        }
        val file = journal()
        assertEquals(if (patched) 2 else 1, lines(file), "the journal before load")

        val loaded = store().load().records.single()

        assertEquals(listOf(1 to 2, 3 to 1), loaded.nativeSegments.map { it.logicalOffset to it.items.size })
        val kept = listOf(CodeModeNativeSegment(1, listOf(big, small)), CodeModeNativeSegment(3, listOf(big)))
        val expected = record(kept).apply { progress.output = copied.progress.output }
        assertTrue(CodeModeJournalEncoding.same(expected.snapshot(), loaded), "every other field is the stored one")
        assertEquals(1, lines(file), "the journal is rewritten as one checkpoint")
        assertTrue(Files.size(file) < MAX_REWRITTEN_BYTES, "the journal stayed ${Files.size(file)} bytes")
    }

    private fun record(natives: List<CodeModeNativeSegment>) = CodeModeRecords.of("alpha", 3).apply {
        phase = CodeModePhase.COMPLETED
        progress.output = "done"
        carry.segments = natives
        sessionId = "session"
        conversationId = "conversation"
        nativeBaseId = "alpha-s2"
        issued += CodeModeIssuedStep("request", emptyList())
        replayAnchors = CodeModeReplayAnchors(
            CodeModeHistoryAnchor("base", 1),
            mapOf(1 to CodeModeHistoryAnchor("rs", 1)),
        )
        sourceState = CodeModeSourceState(complete = true, consumed = true)
    }

    private fun store() = CodexCodeModeStore(stateLocation(), Json { encodeDefaults = true }, {})

    private fun journal(): Path = Files.list(stateLocation().dir).use { files ->
        files.toList().single { it.fileName.toString().endsWith(".jsonl") }
    }

    private fun lines(file: Path): Int = Files.readAllLines(file).count(String::isNotBlank)

    private fun reasoning(id: String, encrypted: String): JsonElement =
        Json.parseToJsonElement("""{"type":"reasoning","id":"$id","summary":[],"encrypted_content":"$encrypted"}""")
}
