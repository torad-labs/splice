// NEW: V4-338 — the trace read from the newest line back: which turns are the newest, when a turn is whole,
// and that a read which holds its turns stops. The files are written by the daemon's own TraceStore.
package splice.head.trace.v4338

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.trace.TraceAsk
import splice.head.trace.TraceRows
import splice.head.trace.TracedTurn
import splice.head.wire.ClientInbound
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import splice.head.wire.TurnTrace
import splice.upstream.sse.WireAttempt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val HEAD = "openrouter"
private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z
private const val DAY_MS = 86_400_000L
private const val SECOND = 1_000L

// DR-186's backstop (JsonlSinkTest's idiom): a read that never reaches its end wedges the suite rather
// than failing, so each case is failed by name past this.
private const val HANG_BACKSTOP_S = 60L

@Timeout(value = HANG_BACKSTOP_S, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TraceTailTest {

    private var now = DAY_ONE
    private val ids = ArrayDeque<String>()
    private val rows = TraceRows(heap = splice.head.syntheticHeapBudget())

    private fun store(dir: Path) = splice.head.syntheticTraceStore(
        ActivityDays(dir, HEAD, 30, WallClock { now }, true),
        HEAD,
        maxBodyChars = 1 shl 20,
        now = WallClock { now },
        ids = TurnIdMint { ids.removeFirst() },
    )

    private fun meta() = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-openrouter--m1",
        upstreamModel = "m1",
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = "alpha-session",
    )

    private fun TraceStore.turn(id: String): TurnTrace {
        ids += id
        return begin(meta(), ClientInbound("POST", "/v1/messages", emptyMap(), """{"turn":"$id"}"""))
    }

    /** One upstream send of this turn, a second after the last record. */
    private fun TurnTrace.send(n: Int = 1) {
        now += SECOND
        val url = "https://openrouter.ai/api/v1"
        attempted(WireAttempt(n, url, emptyMap(), "{}", null, 200, emptyMap(), null, null, 40))
    }

    private fun TurnTrace.end() {
        now += SECOND
        finish("ok", PerfSnapshot(mapOf("total" to 120L), emptyMap()))
    }

    /** A turn that made [sends] upstream sends and ended. */
    private fun TraceStore.done(id: String, sends: Int = 1) {
        val trace = turn(id)
        (1..sends).forEach { trace.send(it) }
        trace.end()
    }

    private fun drained() = assertEquals(true, AsyncFileIo.drain(), "the file lane drained")

    private fun List<TracedTurn>.ids() = map(TracedTurn::id)

    @Test
    fun `the newest turns are the ones written to last, and each comes whole`(@TempDir dir: Path) {
        val store = store(dir)
        // a starts first and ends last: b's attempt and ending lie between a's attempt and a's ending.
        val a = store.turn("a")
        val b = store.turn("b")
        a.send()
        b.send()
        b.end()
        a.end()
        drained()

        val both = rows.turns(dir, HEAD, TraceAsk(last = 2))
        assertEquals(listOf("b", "a"), both.ids(), "oldest first, by each turn's latest record")
        both.forEach { turn ->
            assertEquals(1, turn.attempts.size, turn.id)
            assertNotNull(turn.turn, turn.id)
        }
        val newest = rows.turns(dir, HEAD, TraceAsk(last = 1)).single()
        assertEquals("a", newest.id)
        assertEquals(1, newest.attempts.size, "a's attempt, behind b's records, was not read")
        assertEquals(2, rows.read(dir, HEAD, TraceAsk(last = 1)).onDisk)
    }

    @Test
    fun `a first attempt written after its turn record joins its turn`(@TempDir dir: Path) {
        val store = store(dir)
        store.done("older")
        val late = store.turn("late")
        late.end()
        late.send()
        drained()

        val turn = rows.turns(dir, HEAD, TraceAsk(last = 1)).single()

        assertEquals("late", turn.id)
        assertEquals(1, turn.attempts.size)
        assertNotNull(turn.turn, "the turn record, written before its first attempt, was left behind")
    }

    @Test
    fun `a read that holds its turns never opens an older day, and the count reads every one`(@TempDir dir: Path) {
        val store = store(dir)
        store.done("day-one")
        now = DAY_ONE + DAY_MS
        store.done("day-two-a")
        store.done("day-two-b")
        drained()
        val dayOne = dir.resolve("$HEAD-2026-09-18.jsonl")

        Files.setPosixFilePermissions(dayOne, PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(Files.isReadable(dayOne), "root reads whatever the mode")
            assertEquals(listOf("day-two-a", "day-two-b"), rows.turns(dir, HEAD, TraceAsk(last = 2)).ids())
            assertEquals(listOf("day-two-a"), rows.turns(dir, HEAD, TraceAsk(last = 20, turn = "day-two-a")).ids())
            assertThrows(IOException::class.java) { rows.read(dir, HEAD, TraceAsk(last = 2)) }
        } finally {
            Files.setPosixFilePermissions(dayOne, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a turn whose first attempt is lost is served from what is on disk`(@TempDir dir: Path) {
        val store = store(dir)
        store.done("before")
        store.done("cut", sends = 2)
        drained()
        val day = dir.resolve("$HEAD-2026-09-18.jsonl")
        val lines = Files.readAllLines(day)
        Files.write(day, lines.filterNot { "\"turn\":\"cut\"" in it && "\"attempt\":1," in it })

        val turn = rows.turns(dir, HEAD, TraceAsk(last = 1)).single()

        assertEquals("cut", turn.id)
        assertEquals(listOf("2"), turn.attempts.map { it.getValue("attempt").toString() })
        assertNotNull(turn.turn)
        assertNull(rows.turns(dir, HEAD, TraceAsk(last = 1, turn = "gone")).firstOrNull())
    }
}
