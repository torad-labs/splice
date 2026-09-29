// NEW: V4-421 — a poll of /api/sessions must not walk every head's projects tree for every listed
// session: measured on the everyday daemon, one lookup costs 20-40 ms and the resume route's own search
// 500-700 ms, and the console polls the list. So the verdict per session is held: a session with a
// transcript for a minute, one without for ten seconds (it may write its first message any moment), and
// one that left the list is forgotten.
package splice.sessions.v4421

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.sessions.http.ResumableSessions
import splice.sessions.http.TestTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Files
import java.nio.file.Path

private const val HAS = "cccccccc-0000-4000-8000-000000000001"
private const val LACKS = "cccccccc-0000-4000-8000-000000000002"

class HeldVerdictTest {
    @TempDir lateinit var tmp: Path

    private var now = 1_000_000L
    private val asked = mutableListOf<String>()

    private val file by lazy { Files.writeString(tmp.resolve("$HAS.jsonl"), "{}\n") }

    private val transcripts = TestTranscripts(
        pages = { id, roots, _, _ ->
            asked += id
            if (id == HAS) {
                TranscriptLookup.Found(TranscriptPage(id, file.toString(), emptyList(), null, emptyMap()))
            } else {
                TranscriptLookup.Missing(roots.map { it.toString() })
            }
        },
    )

    private val sessions by lazy { ResumableSessions(transcripts, listOf(tmp), WallClock { now }) }

    private fun listed(vararg ids: String) = sessions.among(ids.toSet())

    private fun asks(block: () -> Unit): List<String> {
        asked.clear()
        block()
        return asked.toList()
    }

    @Test
    fun `a listing asks each session once, and the next poll reads the held answers`() {
        assertEquals(setOf(HAS, LACKS), asks { listed(HAS, LACKS) }.toSet())
        now += 5_000
        assertEquals(emptyList<String>(), asks { listed(HAS, LACKS) }, "5 s on, both answers are held")
    }

    @Test
    fun `a session without a transcript is asked again after ten seconds, one with after a minute`() {
        listed(HAS, LACKS)
        now += 10_000
        assertEquals(listOf(LACKS), asks { listed(HAS, LACKS) }, "the missing one may have written a first message")
        now += 49_000
        assertEquals(listOf(LACKS), asks { listed(HAS, LACKS) }, "59 s after the first look the yes is still held")
        now += 2_000
        val again = asks { listed(HAS, LACKS) }
        assertEquals(listOf(HAS), again, "past the minute the yes is asked again; the no was asked 2 s ago")
    }

    @Test
    fun `a session that left the list is forgotten, so the held answers cannot grow without bound`() {
        listed(HAS, LACKS)
        listed(HAS)
        assertEquals(listOf(LACKS), asks { listed(HAS, LACKS) }, "it came back and was measured again, not remembered")
    }
}
