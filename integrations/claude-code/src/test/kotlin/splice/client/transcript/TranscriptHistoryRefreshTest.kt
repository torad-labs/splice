// NEW: Oct 12, 2026 (splice-lead) — a refresh of the history index lists and stats the transcripts and reads only a new or
// changed one, and reads only what was appended to history.jsonl. The answers stay right through add, change and remove.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private const val ONE = "3f2a9c1e-0000-4000-8000-000000000011"
private const val TWO = "3f2a9c1e-0000-4000-8000-000000000012"
private const val THREE = "3f2a9c1e-0000-4000-8000-000000000013"
private const val PAST_THE_CACHE_MS = 11_000L

class TranscriptHistoryRefreshTest {
    @TempDir lateinit var home: Path

    private var now = 1_000_000L
    private val index = TranscriptHistoryIndex(WallClock { now })
    private val claude: Path get() = Files.createDirectories(home.resolve(".claude"))
    private val roots get() = listOf(SessionHistoryRoot(null, claude))

    private fun refresh(): SessionHistoryScan {
        now += PAST_THE_CACHE_MS
        return index.scan(roots)
    }

    private fun title(text: String) = """{"type":"custom-title","customTitle":"$text"}""" + "\n"

    private fun transcript(id: String, body: String, stampMs: Long): Path {
        val file = claude.resolve("projects/-work-atlas/$id.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, body)
        Files.setLastModifiedTime(file, FileTime.fromMillis(stampMs))
        return file
    }

    private fun names(scan: SessionHistoryScan) = scan.sessions.associate { it.sessionId to it.name }

    @Test
    fun `a transcript added after a scan appears at the next refresh and the others stay`() {
        transcript(ONE, title("First"), 1_000)
        assertEquals(mapOf(ONE to "First"), names(refresh()))

        transcript(TWO, title("Second"), 2_000)

        assertEquals(mapOf(ONE to "First", TWO to "Second"), names(refresh()))
    }

    @Test
    fun `a transcript that changed is read again and one that did not change is not`() {
        val changing = transcript(ONE, title("Before"), 1_000)
        val still = transcript(TWO, title("Aaaaaa"), 2_000)
        refresh()

        Files.writeString(changing, title("After"))
        Files.setLastModifiedTime(changing, FileTime.fromMillis(3_000))
        // Same length, same stamp: a file the index has no reason to look at again keeps what it knew.
        Files.writeString(still, title("Bbbbbb"))
        Files.setLastModifiedTime(still, FileTime.fromMillis(2_000))

        assertEquals(mapOf(ONE to "After", TWO to "Aaaaaa"), names(refresh()))
    }

    @Test
    fun `a transcript that grew is read again`() {
        val file = transcript(ONE, title("Name"), 1_000)
        refresh()

        Files.writeString(file, title("Name") + title("Renamed"))
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000))

        assertEquals(mapOf(ONE to "Renamed"), names(refresh()))
    }

    @Test
    fun `a transcript removed after a scan is gone at the next refresh`() {
        val gone = transcript(ONE, title("Gone"), 1_000)
        transcript(TWO, title("Stays"), 2_000)
        assertEquals(setOf(ONE, TWO), names(refresh()).keys)

        Files.delete(gone)

        assertEquals(mapOf(TWO to "Stays"), names(refresh()))
    }

    @Test
    fun `history rows appended after a scan are added and the earlier ones are not counted twice`() {
        val history = claude.resolve("history.jsonl")
        Files.writeString(history, row(ONE, "first", 1) + row(ONE, "again", 2) + "{not json\n")
        val _ = refresh()

        Files.writeString(history, row(TWO, "new", 3), java.nio.file.StandardOpenOption.APPEND)
        val second = refresh()

        assertEquals(mapOf(ONE to "again", TWO to "new"), names(second))
    }

    @Test
    fun `a history line still being written is read when it is whole and never twice`() {
        val history = claude.resolve("history.jsonl")
        Files.writeString(history, row(ONE, "one", 1) + """{"sessionId":"$TWO","display":"two","timestamp":2""")
        assertEquals(setOf(ONE), names(refresh()).keys, "half a line is not a row yet")

        Files.writeString(history, "}\n" + row(THREE, "three", 3), java.nio.file.StandardOpenOption.APPEND)
        val scan = refresh()

        assertEquals(mapOf(ONE to "one", TWO to "two", THREE to "three"), names(scan))
    }

    @Test
    fun `a history file rewritten shorter is read from its start`() {
        val history = claude.resolve("history.jsonl")
        Files.writeString(history, row(ONE, "one", 1) + row(TWO, "two", 2))
        assertEquals(setOf(ONE, TWO), names(refresh()).keys)

        Files.delete(history)
        Files.writeString(history, row(THREE, "3", 3))

        assertEquals(mapOf(THREE to "3"), names(refresh()))
    }

    private fun row(id: String, display: String, at: Long) =
        """{"sessionId":"$id","display":"$display","project":"/work/atlas","timestamp":$at}""" + "\n"
}
