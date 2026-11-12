// NEW: #523. A retiring daemon finishes the answers it is in the middle of while the next daemon serves everything
// else, so for a few minutes two processes could write one session's cost. The figure a person reads on the status
// line and on Sessions has to come out the same as if one process had served every turn: each turn counted, and
// counted once.
package splice.head.usage

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.NoHeadEvents
import splice.head.perf.PerfRowMeta
import splice.head.perf.PerfStats
import splice.head.perf.PerfTranscriptIds
import splice.head.perf.SessionTotals
import java.nio.file.Path

private const val OPUS = "claude-opus-5-5"
private const val TAG = "a6b15bd7"
private const val SESSION = "a6b15bd7-1c2d-4e5f-8a9b-0c1d2e3f4a5b"

/** The moment both daemons read as now: the turns below ended seconds before it, so no total has gone idle. */
private const val NOW = 4_000L

private val PRICE = TurnPrice(
    ModelCatalog(
        discoveryPrefix = "claude-anthropic--",
        models = listOf(
            ModelEntry(OPUS, contextWindow = 1_000_000, rates = ModelRates(5.00, cacheRead = 0.50, output = 25.00)),
        ),
        defaultContextWindow = 1_000_000,
        pinnedModel = OPUS,
    ),
)

/** One cold turn: 100000 input tokens, none cached, and 1000 output, which is $0.525 on the card above. */
private val COLD = PerfSnapshot(
    marks = emptyMap(),
    counters = mapOf(
        PerfKeys.IN_TOKENS to 100_000L,
        PerfKeys.OUT_TOKENS to 1_000L,
        PerfKeys.CACHED_TOKENS to 0L,
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
    ),
)

class RetiredTurnsTest {

    private val opened = mutableListOf<SessionTotals>()

    @AfterEach
    fun close() {
        AsyncFileIo.drain()
        opened.forEach { it.flushNow() }
    }

    /** One daemon's view of the head: its request log and its sessions' running totals, both in [dir]. */
    private fun daemon(dir: Path): PerfStats = PerfStats(
        dir.resolve("claude-splice-perf.jsonl"),
        totals = SessionTotals(dir.resolve("claude-splice-session-totals.json"), PRICE, WallClock { NOW })
            .also { opened += it },
    )

    private fun turn() = PerfRowMeta(
        model = OPUS,
        outcome = "ok",
        compact = false,
        session = TAG,
        transcript = PerfTranscriptIds(sessionId = SESSION),
    )

    private fun turnsOf(daemon: PerfStats): Long = daemon.totals!!.totalFor(SESSION)!!.models.getValue(OPUS).turns

    private fun usdOf(daemon: PerfStats): Double = daemon.totals!!.totalFor(SESSION)!!.models.getValue(OPUS).usd

    @Test
    fun `a turn finished after its head retired is in the session's cost once, counted by the next daemon`(
        @TempDir tmp: Path,
    ) {
        val retiring = daemon(tmp)
        retiring.record(turn(), COLD, at = 2_000L)
        retiring.retired.retire()
        retiring.totals!!.flushNow()

        // The answer that was in progress at the hand-over ends here, in the process that no longer counts.
        retiring.record(turn(), COLD, at = 3_000L)
        assertEquals(1L, turnsOf(retiring), "the retired process adds nothing to the total it handed over")

        val next = daemon(tmp)
        assertEquals(1L, turnsOf(next), "the next daemon starts from the total the retiring one wrote")
        val left = RetiredTurns(next, TurnSpend(), NoHeadEvents)
        left.fold()
        left.fold()

        assertEquals(2L, turnsOf(next), "the late turn is counted, and a second look counts nothing again")
        assertEquals(1.05, usdOf(next), 1e-9)
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
    }

    @Test
    fun `a retired process leaves the rows it finds for the next daemon and counts none of them`(@TempDir tmp: Path) {
        val retiring = daemon(tmp)
        retiring.record(turn(), COLD, at = 2_000L)
        retiring.retired.retire()
        retiring.totals!!.flushNow()
        retiring.record(turn(), COLD, at = 3_000L)

        // The retiring head's own telemetry looks at the folder at every row it writes.
        RetiredTurns(retiring, TurnSpend(), NoHeadEvents).fold()
        assertEquals(1L, turnsOf(retiring))

        val next = daemon(tmp)
        RetiredTurns(next, TurnSpend(), NoHeadEvents).fold()
        assertEquals(2L, turnsOf(next), "the row was still there for the daemon that counts")
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
    }
}
