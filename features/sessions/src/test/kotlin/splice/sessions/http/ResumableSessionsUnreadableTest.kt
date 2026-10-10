// NEW: Oct 10, 2026 — one unreadable transcript must not take the whole session list down with it.
//
// Found on a walk of the installed daemon (splice-builder2): `GET /api/sessions` answered 500 with
// `IOException: failure (message withheld: it may quote file bytes)` on the everyday machine, while the
// same route answered an empty list on a desk with no sessions. The listing opens a file per listed
// session to decide whether it can be resumed, and a file read can fail for reasons that have nothing to
// do with the session — a tree that went away mid-walk, a directory the daemon cannot enter, a broken
// link. That failure escaped, and every session on the page went with it.
//
// Resumable is a HINT on a row. The listing is the page. So a read that fails is an answer: not
// resumable, with the cause in the log.
package splice.sessions.http

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptPage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val READABLE = "dddddddd-0000-4000-8000-000000000001"
private const val UNREADABLE = "dddddddd-0000-4000-8000-000000000002"

class ResumableSessionsUnreadableTest {
    @TempDir lateinit var tmp: Path

    private val said = mutableListOf<String>()

    /** The id whose file cannot be read throws the way a real reader does, from inside the port. */
    private fun sessions(): ResumableSessions {
        val file = Files.writeString(tmp.resolve("$READABLE.jsonl"), "{\"type\":\"user\"}\n")
        val transcripts = TestTranscripts(
            pages = { id, _, _, _ ->
                if (id == UNREADABLE) throw IOException("/home/someone/.claude/projects/x: permission denied")
                TranscriptLookup.Found(TranscriptPage(id, file.toString(), emptyList(), null, emptyMap()))
            },
        )
        return ResumableSessions(transcripts, listOf(tmp), WallClock { 1_000_000L }) { said += it }
    }

    /** What a row says about [id], or null when the listing claimed nothing for it. */
    private fun markOf(measured: Resumability, id: String): Boolean? =
        buildJsonObject { measured.mark(this, id) }[("resumable")]?.jsonPrimitive?.content?.toBoolean()

    @Test
    fun `a transcript that cannot be read leaves its own row unresumable and every other row measured`() {
        val measured = sessions().among(setOf(READABLE, UNREADABLE))

        assertEquals(true, markOf(measured, READABLE), "the session splice can read is still offered for resume")
        assertEquals(
            false,
            markOf(measured, UNREADABLE),
            "and the one it cannot read is simply not offered, rather than ending the listing",
        )
    }

    @Test
    fun `the reason the transcript could not be read goes to the log`() {
        sessions().among(setOf(UNREADABLE))

        val line = said.single()
        assertTrue(line.startsWith("[sessions] a transcript could not be read"), line)
        assertFalse(
            line.contains("permission denied"),
            "a file reader's own message is withheld, because it can quote the file's bytes: $line",
        )
        assertTrue(
            line.contains("ResumableSessionsUnreadableTest.kt:"),
            "so the code location is what is left to diagnose from, and it never quotes content: $line",
        )
    }
}
