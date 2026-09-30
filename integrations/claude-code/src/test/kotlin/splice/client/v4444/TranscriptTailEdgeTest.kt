// NEW: V4-444 — complete records, tool-result joins and changed file snapshots.
package splice.client.v4444

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

class TranscriptTailEdgeTest {
    @Test
    fun `tool results share the page's preceding call name`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(
            listOf(
                ACTIVITY_USER,
                """{"type":"assistant","message":{"id":"synthetic-call","content":[{"type":"tool_use","id":"synthetic-result","name":"SyntheticTool","input":{}}]}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"synthetic-result","content":"synthetic result"}]}}""",
            ),
        )
        val last = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals(TranscriptRole.TOOL, last?.role)
        assertEquals("SyntheticTool", last?.tool)
        assertEquals("synthetic result", last?.text)
    }

    @Test
    fun `a tool result finds its call before a chunk splitting the assistant reply`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        val thinking = "x".repeat(70_000)
        fixture.write(
            listOf(
                ACTIVITY_USER,
                """{"type":"assistant","message":{"id":"synthetic-call","content":[{"type":"tool_use","id":"synthetic-result","name":"SyntheticTool","input":{}}]}}""",
                """{"type":"assistant","message":{"id":"synthetic-call","content":[{"type":"thinking","thinking":"$thinking"}]}}""",
                """{"type":"assistant","message":{"id":"synthetic-call","content":[{"type":"text","text":"calling"}]}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"synthetic-result","content":"synthetic result"}]}}""",
            ),
        )
        val last = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals("SyntheticTool", last?.tool, "a partial preceding reply must not hide the call")
        assertEquals("synthetic result", last?.text)
        assertTrue(fixture.bytes in 1..262_144)
    }

    @Test
    fun `a complete result remains visible with unknown tool when its call is outside the ceiling`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(emptyList())
        Files.newBufferedWriter(fixture.file).use { out ->
            val filler = "x".repeat(1024)
            repeat(17 * 1024) { out.write(filler) }
            out.write("\n")
            out.write(
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"synthetic-absent","content":"synthetic result"}]}}""" + "\n",
            )
        }
        val last = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals(TranscriptRole.TOOL, last?.role)
        assertEquals("synthetic result", last?.text)
        assertEquals(null, last?.tool)
        assertEquals(16_777_216L, fixture.bytes, "the hard ceiling remains bounded")
        val read = fixture.bytes
        assertEquals(last, fixture.reader.last(ACTIVITY_ID, listOf(tmp)))
        assertEquals(read, fixture.bytes)
    }

    @Test
    fun `an unfinished append is ignored until it forms a message`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(listOf(ACTIVITY_USER))
        assertEquals("synthetic start", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
        Files.writeString(fixture.file, """{"type":"user","message":{"content":""", StandardOpenOption.APPEND)
        assertEquals("synthetic start", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
        Files.writeString(fixture.file, """"completed"}}""" + "\n", StandardOpenOption.APPEND)
        assertEquals("completed", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
    }

    @Test
    fun `shrink and same size rewrite invalidate the retained tail`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(listOf(ACTIVITY_FIRST, ACTIVITY_LAST))
        assertEquals("First token=[redacted]\n\nLast", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
        fixture.write(listOf(ACTIVITY_USER))
        assertEquals("synthetic start", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
        val old = Files.getLastModifiedTime(fixture.file)
        Files.writeString(fixture.file, ACTIVITY_USER.replace("start", "other") + "\n")
        Files.setLastModifiedTime(fixture.file, FileTime.fromMillis(old.toMillis() + 1_000))
        assertEquals("synthetic other", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
    }

    @Test
    fun `a three megabyte image result keeps its role and preceding tool instead of disappearing`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        val image = "A".repeat(3 * 1024 * 1024)
        fixture.write(
            listOf(
                ACTIVITY_USER,
                """{"type":"assistant","message":{"id":"synthetic-image-call","content":[{"type":"tool_use","id":"synthetic-image","name":"SyntheticScreenshot","input":{}}]}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"synthetic-image","content":[{"type":"image","source":{"type":"base64","data":"$image"}}]}]}}""",
            ),
        )
        val last = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals(TranscriptRole.TOOL, last?.role)
        assertEquals("SyntheticScreenshot", last?.tool)
        assertEquals("", last?.text, "image data is not transcript text")
        assertTrue(fixture.bytes in 1..4_194_304, "read ${fixture.bytes} bytes")
        val read = fixture.bytes
        assertEquals(last, fixture.reader.last(ACTIVITY_ID, listOf(tmp)))
        assertEquals(read, fixture.bytes, "the large record is a one-time read per snapshot")
    }
}
