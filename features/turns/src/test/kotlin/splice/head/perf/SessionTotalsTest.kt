// NEW: V4-244 — the per-session running total the status line prices a session of any length from.
// Each test pins one property the figure rests on: every turn at its own model's card, nothing from
// another session, the total surviving a restart, and [PerfSessionTotal.fromMs] never claiming rows
// the total did not count, even after a session's total was dropped and its tag came back.
package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val SONNET = "claude-sonnet-5"
private const val OPUS = "claude-opus-5-5"
private const val TAG = "a6b15bd7"
private const val SESSION = "a6b15bd7-1c2d-4e5f-8a9b-0c1d2e3f4a5b"

private val PRICE = TurnPrice(
    ModelCatalog(
        discoveryPrefix = "claude-anthropic--",
        models = listOf(
            ModelEntry(OPUS, contextWindow = 1_000_000, rates = ModelRates(5.00, cacheRead = 0.50, output = 25.00)),
            ModelEntry(SONNET, contextWindow = 1_000_000, rates = ModelRates(3.00, cacheRead = 0.30, output = 15.00)),
        ),
        defaultContextWindow = 1_000_000,
        pinnedModel = OPUS,
    ),
)

/** One cold turn: 100000 input tokens, none cached, and 1000 output. */
private val COLD = mapOf(PerfKeys.IN_TOKENS to 100_000L, PerfKeys.OUT_TOKENS to 1_000L)

class SessionTotalsTest {

    private var now = 1_000L
    private val lines = mutableListOf<String>()

    private fun store(file: Path, price: TurnPrice = PRICE) =
        SessionTotals(file, price, WallClock { now }, LogSink { lines += it })

    @Test
    fun `each turn is priced at the card of the model it ran on when its row is appended`(@TempDir tmp: Path) {
        val totals = store(tmp.resolve("t.json"))
        totals.add(TAG, SONNET, COLD, 2_000L)
        totals.add(TAG, OPUS, COLD, 3_000L)
        totals.add(TAG, OPUS, COLD + (PerfKeys.CACHED_TOKENS to 50_000L), 4_000L)

        val total = totals.totalFor(SESSION)!!
        //   the Sonnet turn: 100000 * 3.00 + 1000 * 15.00                  = 0.315
        //   the Opus turns:  100000 * 5.00 + 1000 * 25.00                  = 0.525
        //                    50000 * 5.00 + 50000 * 0.50 + 1000 * 25.00    = 0.300
        assertEquals(0.315, total.models.getValue(SONNET).usd, 1e-12)
        assertEquals(0.825, total.models.getValue(OPUS).usd, 1e-12)
        val opus = total.models.getValue(OPUS)
        val counted = with(opus) { listOf(turns, inTokens, cachedTokens, cacheWriteTokens, outTokens, unpricedTurns) }
        assertEquals(listOf(2L, 200_000L, 50_000L, 0L, 2_000L, 0L), counted)
        val byTurn = PRICE.usd(OPUS, COLD)!! + PRICE.usd(OPUS, COLD + (PerfKeys.CACHED_TOKENS to 50_000L))!!
        assertEquals(byTurn, opus.usd, 1e-12, "TurnPrice's arithmetic, turn by turn")
    }

    @Test
    fun `a turn with no card counts as unpriced, and a turn that spent nothing counts as nothing`(@TempDir tmp: Path) {
        val totals = store(tmp.resolve("t.json"))
        totals.add(TAG, "gpt-6-sol", COLD, 2_000L)
        totals.add(TAG, OPUS, emptyMap(), 3_000L)
        totals.add(TAG, OPUS, mapOf(PerfKeys.IN_TOKENS to 0L, PerfKeys.OUT_TOKENS to 0L), 4_000L)

        val total = totals.totalFor(SESSION)!!
        val sol = total.models.getValue("gpt-6-sol")
        assertEquals(1L, sol.unpricedTurns, "no card: unpriced, never zero dollars")
        assertEquals(0.0, sol.usd)
        assertEquals(100_000L, sol.inTokens, "its tokens are still kept")
        assertEquals(setOf("gpt-6-sol"), total.models.keys, "a local refusal spent nothing and is not a turn to price")
    }

    @Test
    fun `a session's total holds its rows and no other session's`(@TempDir tmp: Path) {
        val totals = store(tmp.resolve("t.json"))
        totals.add(TAG, OPUS, COLD, 2_000L)
        totals.add("ffff0000", OPUS, COLD, 2_500L)

        assertEquals(1L, totals.totalFor(SESSION)!!.models.getValue(OPUS).turns)
        assertNull(totals.totalFor("0123abcd-none"), "a session with no counted row has no total")
        assertNull(totals.totalFor(""), "an empty id matches nothing, never a head-wide total")
    }

    @Test
    fun `the total survives a restart, and so does the moment the store began`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.json")
        val first = store(file)
        first.add(TAG, OPUS, COLD, 2_000L)
        first.flushNow()

        now = 9_000L
        val second = store(file)
        second.add(TAG, OPUS, COLD, 9_500L)
        second.add("ffff0000", OPUS, COLD, 9_600L)

        val total = second.totalFor(SESSION)!!
        assertEquals(2L, total.models.getValue(OPUS).turns, "the reloaded total goes on counting")
        assertEquals(1_000L, total.fromMs, "the store began at 1000, and every row since was counted")
        assertEquals(1_000L, second.totalFor("ffff0000-x")!!.fromMs, "a restart is not a gap: nothing went uncounted")
    }

    @Test
    fun `a session dropped for idleness comes back as a total that claims none of its old rows`(@TempDir tmp: Path) {
        val totals = store(tmp.resolve("t.json"))
        totals.add("11111111", OPUS, COLD, 1_500L) // a live session, active throughout
        totals.add(TAG, OPUS, COLD, 2_000L) // goes idle after this row

        now = 2_000L + SESSION_IDLE_RETENTION_MS / 2
        totals.add("11111111", OPUS, COLD, now)
        now = 2_000L + SESSION_IDLE_RETENTION_MS + 1
        totals.add("11111111", OPUS, COLD, now)
        assertNull(totals.totalFor(SESSION), "idle past the retention, dropped")

        totals.add(TAG, OPUS, COLD, now + 1)
        val back = totals.totalFor(SESSION)!!
        assertEquals(1L, back.models.getValue(OPUS).turns)
        assertTrue(back.fromMs > 2_000L, "the new total must not claim the dropped row at 2000: from=${back.fromMs}")
        assertEquals(1_000L, totals.totalFor("11111111-x")!!.fromMs, "a live session's total keeps its own start")
    }

    @Test
    fun `past the session cap the least recently active total goes, and the same rule holds`(@TempDir tmp: Path) {
        val totals = store(tmp.resolve("t.json"))
        for (i in 0..MAX_SESSION_TOTALS) totals.add("s%07d".format(i), OPUS, COLD, 2_000L + i)

        assertNull(totals.totalFor("s0000000-x"), "the oldest of ${MAX_SESSION_TOTALS + 1} is dropped")
        assertNotNull(totals.totalFor("s0000001-x"))
        totals.add("s0000000", OPUS, COLD, 9_000L)
        assertTrue(totals.totalFor("s0000000-x")!!.fromMs > 2_000L, "its old row is not claimed")
    }

    @Test
    fun `a corrupt file degrades to empty, says so, and claims nothing from before it`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.json").also { Files.writeString(it, "{not json") }
        now = 7_000L
        val totals = store(file)
        totals.add(TAG, OPUS, COLD, 7_500L)

        assertEquals(7_000L, totals.totalFor(SESSION)!!.fromMs, "the lost history is not claimed")
        assertTrue(lines.single().contains("[session-totals]"), lines.toString())
    }

    /** The coalesced write marks the file not clean; only a head stop's write marks it clean. A file no
     *  stop closed may be short of the rows its last second counted, so it claims nothing. */
    @Test
    fun `a file no head stop closed starts over, and says so`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.json")
        val first = store(file)
        first.add(TAG, OPUS, COLD, 2_000L)
        first.flushNow()
        Files.writeString(file, Files.readString(file).replace("\"clean\":true", "\"clean\":false"))

        now = 9_000L
        val second = store(file)
        second.add(TAG, OPUS, COLD, 9_500L)
        val total = second.totalFor(SESSION)!!
        val fresh = total.models.getValue(OPUS).turns to total.fromMs
        assertEquals(1L to 9_000L, fresh, "a fresh total that claims nothing older")
        assertTrue(lines.single().contains("not closed by a head stop"), lines.toString())
    }

    /** A turn that records after its head's stop flush must not leave the clean mark standing over a
     *  file that lacks it: the first row after the stop writes the file through, not clean. */
    @Test
    fun `a row after the stop flush takes the clean mark away at once`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.json")
        val totals = store(file)
        totals.add(TAG, OPUS, COLD, 2_000L)
        totals.flushNow()
        assertTrue("\"clean\":true" in Files.readString(file))

        totals.add(TAG, OPUS, COLD, 3_000L)
        assertTrue("\"clean\":false" in Files.readString(file), "written through before add returns")
    }

    @Test
    fun `an absent file is a quiet first run`(@TempDir tmp: Path) {
        store(tmp.resolve("t.json")).add(TAG, OPUS, COLD, 2_000L)
        assertTrue(lines.isEmpty(), lines.toString())
    }
}
