// NEW: V4-444 — activity uses the page's main-thread record rules and merged messages.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path

internal const val ACTIVITY_USER = """{"type":"user","message":{"content":"synthetic start"}}"""
internal const val ACTIVITY_FIRST = """{"type":"assistant","timestamp":"1970-01-01T00:00:00.007Z","message":{"id":"synthetic-reply","content":[{"type":"text","text":"First token=abcdefgh123456"}]}}"""
internal const val ACTIVITY_LAST = """{"type":"assistant","message":{"id":"synthetic-reply","content":[{"type":"text","text":"Last"}]}}"""

class TranscriptLastTest {
    @Test
    fun `the trailing reply is merged redacted and ignores non conversation records`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(
            listOf(
                ACTIVITY_USER,
                ACTIVITY_FIRST,
                ACTIVITY_LAST,
                """{"type":"assistant","isSidechain":true,"message":{"id":"synthetic-side","content":[{"type":"text","text":"hidden"}]}}""",
                """{"type":"attachment","attachment":{"text":"not a message"}}""",
                """{"type":"system","subtype":"turn_duration"}""",
                "{not-json",
            ),
        )
        val last = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals("First token=[redacted]\n\nLast", last?.text)
        assertEquals(7L, last?.ts)
        val page = fixture.reader.page(ACTIVITY_ID, listOf(tmp), null, 100) as TranscriptLookup.Found
        assertEquals(page.page.messages.last(), last, "tail and page have one copy of the rules")
    }

    @Test
    fun `a tool call keeps the page role and tool name`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(
            listOf(
                ACTIVITY_USER,
                """{"type":"assistant","message":{"id":"synthetic-call","content":[{"type":"tool_use","id":"synthetic-tool-id","name":"SyntheticTool","input":{"text":"ok"}}]}}""",
            ),
        )
        val last = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals(TranscriptRole.ASSISTANT, last?.role)
        assertEquals("SyntheticTool", last?.tool)
        assertEquals("""{"text":"ok"}""", last?.text)
    }

    @Test
    fun `root priority symlink aliases and a missing transcript follow locate`(@TempDir tmp: Path) {
        val first = ActivityTranscript(tmp.resolve("first"))
        val second = ActivityTranscript(tmp.resolve("second"))
        second.write(listOf(ACTIVITY_USER))
        val roots = listOf(first.root, second.root)
        assertEquals("synthetic start", first.reader.last(ACTIVITY_ID, roots)?.text)
        first.write(listOf(ACTIVITY_FIRST, ACTIVITY_LAST))
        assertEquals("First token=[redacted]\n\nLast", first.reader.last(ACTIVITY_ID, roots)?.text)
        val linked = Files.createDirectories(tmp.resolve("linked"))
        Files.createSymbolicLink(linked.resolve("projects"), first.root.resolve("projects"))
        val read = first.bytes
        assertEquals("First token=[redacted]\n\nLast", first.reader.last(ACTIVITY_ID, listOf(linked, first.root))?.text)
        assertEquals(read, first.bytes, "a realpath alias reuses the same stamp cache")
        assertNull(first.reader.last("synthetic-missing", roots))
        assertNull(first.reader.last("../escape", roots))
    }
}
