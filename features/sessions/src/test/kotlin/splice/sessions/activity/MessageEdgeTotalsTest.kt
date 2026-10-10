// NEW: V4-444 — the Sessions listing's edge counts come from a store that does not grow with the window.
//
// The row cache held every edge of every day and passed its 16 MiB allowance on his desk (35,579 edges over 20 days),
// which took `GET /api/sessions` to a 500. The counts a row shows are `{sent, received, last_at}` per session, and these
// tests drive the store the way the listing does, over real day files, and hold it to two things:
//
//  · the counts are EXACTLY what the edge list would have given for the same files (the rule a row follows is
//    EdgeIndex.mine), through appends, a file replaced under it and an id written twice; and
//  · what it keeps is not proportional to the edges: a window the row cache refuses for its allowance is answered whole
//    here, while the same allowance still refuses a store that really outgrows it.
package splice.sessions.activity

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.http.EdgeIndex
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND

private const val DAY_ONE = 1_789_725_600_000L
private const val DAY_MS = 86_400_000L
private const val SESSIONS = 40
private const val EDGES_PER_DAY = 6_000
private const val NO_HOLDER = "<null>"

class MessageEdgeTotalsTest {
    @TempDir
    lateinit var dir: Path

    private var clock = DAY_ONE

    private fun day(index: Int): Path {
        val date = java.time.Instant.ofEpochMilli(DAY_ONE + index * DAY_MS).toString().take(10)
        return dir.resolve("edges-$date.jsonl")
    }

    private fun store(maxBytes: Long = EDGE_CACHE_BYTES): MessageEdgeStore = MessageEdgeStore(
        ActivityDays(dir, EDGES_PREFIX, 7, WallClock { clock }),
        DayFiles(dir, EDGES_PREFIX),
        7,
        true,
        MessageEdgeCodec(),
        null,
        maxBytes,
    )

    private fun line(
        id: String,
        from: String,
        to: String,
        at: Long,
        toSession: String? = null,
    ) = buildString {
        append("""{"from":"$from","to":"$to","at":$at,"id":"$id"""")
        when (toSession) {
            null -> Unit
            NO_HOLDER -> append(""","to_session":null""")
            else -> append(""","to_session":"$toSession"""")
        }
        append("}\n")
    }

    /** What the listing showed before: the counts [EdgeIndex] gives the same edges. */
    private fun expected(store: MessageEdgeStore, id: String, address: String?): Triple<Int, Int, Long?> {
        val edges = EdgeIndex(store.edges(), emptyList()).edgesOf(id, address).map { it.jsonObject }
        val sent = edges.count { it.getValue("direction").jsonPrimitive.content == "out" }
        return Triple(sent, edges.size - sent, edges.maxOfOrNull { it.getValue("at").jsonPrimitive.long })
    }

    private fun assertSame(store: MessageEdgeStore, id: String, address: String?) {
        val got = store.totals().of(id, address)
        val (sent, received, last) = expected(store, id, address)
        val counted = Triple(got.sent, got.received, got.lastAt)
        assertEquals(Triple(sent, received, last), counted, "counts for $id at $address")
    }

    @Test
    fun `counts match the edge list for every way an edge reaches a session`() {
        assertTrue(AsyncFileIo.drain())
        Files.writeString(
            day(0),
            line("a", "s1", "addr-2", DAY_ONE + 1, toSession = "s2") +
                line("b", "s2", "addr-1", DAY_ONE + 2, toSession = "s1") +
                line("c", "s1", "addr-1", DAY_ONE + 3, toSession = "s1") + // sent to itself: an out, never an in
                line("d", "s3", "addr-1", DAY_ONE + 4) + // legacy: no session stored, the address names the receiver
                line("e", "s1", "addr-1", DAY_ONE + 5) + // legacy, sent by the very session at that address: an out
                line("f", "s2", "addr-1", DAY_ONE + 6, toSession = NO_HOLDER) + // no holder: counted as legacy
                line("g", "s9", "addr-nobody", DAY_ONE + 7),
        )
        val store = store()
        val sessions = listOf("s1" to "addr-1", "s2" to "addr-2", "s3" to null, "s9" to null, "unknown" to "addr-1")
        for (session in sessions) {
            assertSame(store, session.first, session.second)
        }
    }

    @Test
    fun `an id written twice in one file counts once, and appends and a replaced file read back true`() {
        assertTrue(AsyncFileIo.drain())
        val twice = line("a", "s1", "x", DAY_ONE, toSession = "s2") +
            line("a", "s1", "x", DAY_ONE + 9, toSession = "s2")
        Files.writeString(day(0), twice)
        val store = store()
        assertEquals(1, store.totals().of("s1", null).sent, "the repeat is the same call recorded twice")
        assertSame(store, "s1", null)
        Files.writeString(day(0), line("b", "s1", "x", DAY_ONE + 20, toSession = "s2"), APPEND)
        assertSame(store, "s1", null)
        assertSame(store, "s2", null)
        // replaced: older rows are gone
        Files.writeString(day(0), line("only", "s5", "x", DAY_ONE + 30, toSession = "s6"))
        assertSame(store, "s1", null)
        assertSame(store, "s5", null)
        assertEquals(0, store.totals().of("s1", null).sent)
    }

    @Test
    fun `a file that grew after a newer one appeared is read again from its start`() {
        assertTrue(AsyncFileIo.drain())
        Files.writeString(day(0), line("a", "s1", "x", DAY_ONE, toSession = "s2"))
        clock = DAY_ONE + DAY_MS
        Files.writeString(day(1), line("b", "s1", "x", DAY_ONE + DAY_MS, toSession = "s2"))
        val store = store()
        assertEquals(2, store.totals().of("s1", null).sent)
        Files.writeString(day(0), line("c", "s1", "x", DAY_ONE + 5, toSession = "s2"), APPEND)
        val sent = store.totals().of("s1", null).sent
        assertEquals(3, sent, "the older file is no longer the newest and still reads true")
        assertSame(store, "s2", null)
    }

    @Test
    fun `a window the row cache refuses for its allowance is answered whole, and a real overrun still refuses`() {
        assertTrue(AsyncFileIo.drain())
        for (index in 0..2) {
            clock = DAY_ONE + index * DAY_MS
            Files.writeString(
                day(index),
                (0 until EDGES_PER_DAY).joinToString("") { n ->
                    val at = DAY_ONE + index * DAY_MS + n
                    line("d$index-$n", "s${n % SESSIONS}", "x", at, toSession = "s${(n + 1) % SESSIONS}")
                },
            )
        }
        val allowance = 1_024L * 1_024L
        // 18,000 edges would weigh about 7 MiB as rows; the counts are one entry per session.
        assertThrows(IOException::class.java) { store(allowance).edges() }
        val counted = store(allowance).totals()
        assertEquals(EDGES_PER_DAY * 3 / SESSIONS, counted.of("s0", null).sent)
        assertEquals(EDGES_PER_DAY * 3 / SESSIONS, counted.of("s1", null).received)
        // the guard against a runaway writer is still there: what is kept still has to fit its allowance
        assertThrows(IOException::class.java) { store(2_048L).totals() }
    }
}
