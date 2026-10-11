// NEW: V4-421 — a poll of /api/sessions must not walk every head's projects tree for every listed
// session: measured on the everyday daemon, one lookup costs 20-40 ms (29 sessions together about a
// second) and the resume route's own search 500-700 ms, and the console polls the list. So the verdict
// per session is held: a session with a transcript for five to six minutes (each id's own spread keeps the
// ones first measured together from expiring together), one without for ten seconds (it may write its
// first message any moment), and one that left the list is forgotten.
package splice.sessions.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Files
import java.nio.file.Path

private const val HAS = "cccccccc-0000-4000-8000-000000000001"
private const val LACKS = "cccccccc-0000-4000-8000-000000000002"

class ResumableSessionsHeldVerdictTest {
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
    fun `a session without a transcript is asked again after ten seconds, one with after five to six minutes`() {
        listed(HAS, LACKS)
        now += 10_000
        assertEquals(listOf(LACKS), asks { listed(HAS, LACKS) }, "the missing one may have written a first message")
        now += 289_000
        assertEquals(listOf(LACKS), asks { listed(HAS, LACKS) }, "299 s after the first look the yes is still held")
        now += 62_000
        val again = asks { listed(HAS, LACKS) }
        assertTrue(HAS in again, "past six minutes the yes is asked again, whatever its spread: $again")
    }

    @Test
    fun `the sessions first measured together do not all expire in the same poll`() {
        val ids = (1..20).map { "cccccccc-0000-4000-8000-0000000001%02d".format(it) }
        ids.forEach { Files.writeString(tmp.resolve("$it.jsonl"), "{}\n") }
        val spread = ResumableSessions(
            TestTranscripts(
                pages = { id, _, _, _ ->
                    asked += id
                    val path = tmp.resolve("$id.jsonl").toString()
                    TranscriptLookup.Found(TranscriptPage(id, path, emptyList(), null, emptyMap()))
                },
            ),
            listOf(tmp),
            WallClock { now },
        )
        spread.among(ids.toSet())
        now += 295_000
        asked.clear()
        spread.among(ids.toSet())
        assertEquals(0, asked.size, "295 s on, every yes is still held")
        val renewed = (1..8).map {
            now += 10_000
            asked.clear()
            spread.among(ids.toSet())
            asked.size
        }
        assertEquals(ids.size, renewed.sum(), "each of the 20 is renewed once by 375 s: $renewed")
        assertTrue(renewed.max() < ids.size, "no single 10 s poll renews all 20 together: $renewed")
    }

    @Test
    fun `a session that left the list is forgotten, so the held answers cannot grow without bound`() {
        listed(HAS, LACKS)
        listed(HAS)
        assertEquals(listOf(LACKS), asks { listed(HAS, LACKS) }, "it came back and was measured again, not remembered")
    }
}
