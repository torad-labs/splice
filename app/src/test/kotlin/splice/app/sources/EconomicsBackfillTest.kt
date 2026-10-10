// If splice holds a turn, Usage counts it (Marlin, Oct 10, 2026): the hours the rollup has no bucket
// for are built from the request records splice still holds, so the view reaches back as far as the
// turns do and not only as far as the hourly file happens to.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.perf.HistoryWindow
import splice.core.turn.UsageHistory
import splice.core.util.WallClock
import splice.head.usage.EconomicsStore
import splice.head.usage.TurnBytes
import splice.head.usage.TurnEconomics
import splice.head.usage.TurnTokens
import splice.head.usage.TurnTools
import splice.usage.economics.EconomicsRead
import splice.usage.economics.EconomicsRow
import java.nio.file.Files
import java.nio.file.Path

private const val HOUR_MS = 3_600_000L
private const val NOW_MS = 10 * HOUR_MS + 123

/** One turn's request record, in the hour [at] names. Same numbers as [work]. */
private fun rowAt(at: Long): String =
    """{"ts":$at,"model":"synthetic","outcome":"ok","req_bytes":100,"upstream_req_bytes":50,""" +
        """"tools_eager":3,"tools_deferred":2,"in_tokens":5,"cached_tokens":2,""" +
        """"cache_write_tokens":1,"out_tokens":10}"""

/** A pre-marker liveness probe, by the fingerprint LivenessProbe.legacyRow measures. */
private fun probeAt(at: Long): String =
    """{"ts":$at,"model":"","outcome":"error:upstream-failed","req_bytes":30,""" +
        """"upstream_req_bytes":15,"tools_eager":2,"tools_deferred":1}"""

/** A turn the budget refused before any attempt: a perf row the rollup never recorded. */
private fun refusedAt(at: Long): String =
    """{"ts":$at,"model":"synthetic","outcome":"error:budget-blocked","attempts":0,"req_bytes":40,""" +
        """"upstream_req_bytes":0}"""

class EconomicsBackfillTest {
    private val card = ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(ModelEntry("synthetic", contextWindow = 1000, rates = ModelRates(3.0, 0.3, 15.0, 6.0))),
        defaultContextWindow = 1000,
    )

    @Test
    fun `an hour splice holds the turns for, and no total for, is built from those turns`(@TempDir dir: Path) {
        val perf = dir.resolve("head-perf.jsonl")
        // Three hours of turns on disk; the rollup holds only the newest. The OLDEST is left out: a
        // generation can begin mid-hour, so its sums would be a part of an hour read as the whole.
        Files.writeString(perf, listOf(5 * HOUR_MS, 6 * HOUR_MS, NOW_MS).joinToString("\n") { rowAt(it) } + "\n")
        val store = store(dir)
        store.record(work())

        val rows = source(store, perf).rows()

        assertEquals(
            listOf(6 * HOUR_MS, 10 * HOUR_MS),
            rows.map { it.hour },
            "the middle hour is built, the oldest one left out",
        )
        val built = rows.first()
        val recorded = rows.last()
        assertEquals(recorded.counts, built.counts, "a rebuilt hour counts its turns the way the rollup would")
        assertEquals(recorded.tokens, built.tokens, "and sums the same tokens")
        assertEquals(recorded.bytes, built.bytes, "and the same bytes, which is what the history bar is drawn from")
        assertEquals(recorded.cost.costUsd, built.cost.costUsd, "priced at the card splice holds now")
        assertEquals(0L, built.cost.unpricedTurns)
    }

    @Test
    fun `the hour the rollup recorded is never replaced by one read back from rows`(@TempDir dir: Path) {
        val perf = dir.resolve("head-perf.jsonl")
        // The newest hour's row says 900 input tokens; the hour itself was recorded with 5.
        Files.writeString(
            perf,
            listOf(5 * HOUR_MS, 6 * HOUR_MS).joinToString("\n") { rowAt(it) } + "\n" +
                rowAt(NOW_MS).replace(""""in_tokens":5""", """"in_tokens":900""") + "\n",
        )
        val store = store(dir)
        store.record(work())

        val rows = source(store, perf).rows()

        assertEquals(5L, rows.last().tokens.inTokens, "the hour summed from the turns as they ran is the authority")
    }

    @Test
    fun `a record splice cannot read leaves every hour alone, and the next read tries again`(@TempDir dir: Path) {
        val perf = dir.resolve("head-perf.jsonl")
        Files.writeString(
            perf,
            rowAt(5 * HOUR_MS) + "\n" + rowAt(6 * HOUR_MS) + "\n" +
                "{\"ts\":21600001,\"model\"\u0000torn\n" + rowAt(NOW_MS) + "\n",
        )
        val store = store(dir)
        store.record(work())
        val reader = source(store, perf)

        assertEquals(listOf(10 * HOUR_MS), reader.rows().map { it.hour }, "a torn record means no hour is known whole")

        Files.writeString(
            perf,
            listOf(5 * HOUR_MS, 6 * HOUR_MS, NOW_MS).joinToString("\n") { rowAt(it) } + "\n",
        )
        assertEquals(
            listOf(6 * HOUR_MS, 10 * HOUR_MS),
            reader.rows().map { it.hour },
            "the refusal is not remembered: once the records read whole, the gap is filled",
        )
    }

    @Test
    fun `the gap is closed in the hourly file, so the next read is the file and not another scan`(
        @TempDir dir: Path,
    ) {
        val perf = dir.resolve("head-perf.jsonl")
        Files.writeString(
            perf,
            listOf(5 * HOUR_MS, 6 * HOUR_MS, NOW_MS).joinToString("\n") { rowAt(it) } + "\n",
        )
        val store = store(dir)
        store.record(work())
        source(store, perf).rows()
        store.flushNow()

        assertTrue(
            Files.readString(dir.resolve("economics.json")).contains("\"hour\":${6 * HOUR_MS}"),
            "the built hour is written where the rollup keeps its hours",
        )
        // Read again with no request records wired at all: nothing can scan, nothing can reprice,
        // and the built hour is still there, which is what "written, not derived per read" means.
        val fromTheFileAlone = EconomicsStoreSource(store(dir)).rows().map { it.hour }
        assertEquals(
            listOf(6 * HOUR_MS, 10 * HOUR_MS),
            fromTheFileAlone,
            "the hour stands on its own once written, and is trimmed by the window like any other",
        )
    }

    /** An hour the rollup has to be rebuilt from rows that include a legacy probe. ProbeEconomics
     *  reconciles every recorded hour against the rows behind it and refuses to deduct anything at
     *  all when they disagree, so an hour rebuilt WITHOUT its probe hides that head's whole hourly
     *  history behind a sentence, not just the hour. */
    @Test
    fun `an hour rebuilt from rows that hold a legacy probe is still reconciled and read`(@TempDir dir: Path) {
        val perf = dir.resolve("head-perf.jsonl")
        Files.writeString(
            perf,
            listOf(5 * HOUR_MS, 6 * HOUR_MS).joinToString("\n") { rowAt(it) } + "\n" +
                probeAt(6 * HOUR_MS) + "\n" + rowAt(NOW_MS) + "\n",
        )
        val store = store(dir)
        store.record(work())

        val rows = source(store, perf).rows()

        assertEquals(
            listOf(6 * HOUR_MS, 10 * HOUR_MS),
            rows.map { it.hour },
            "the rebuilt hour is there, which means the hour it was rebuilt into reconciled",
        )
        assertEquals(
            1L,
            rows.first().counts.turns,
            "the probe is counted into the rebuilt hour and then deducted from it, like any recorded hour",
        )
    }

    /** The other half of the same agreement: a turn the budget refused before any attempt is written
     *  to the request log and never to the rollup, so an hour rebuilt WITH it disagrees the same way. */
    @Test
    fun `a refusal that never reached a provider is not part of a rebuilt hour`(@TempDir dir: Path) {
        val perf = dir.resolve("head-perf.jsonl")
        Files.writeString(
            perf,
            listOf(5 * HOUR_MS, 6 * HOUR_MS).joinToString("\n") { rowAt(it) } + "\n" +
                refusedAt(6 * HOUR_MS) + "\n" + rowAt(NOW_MS) + "\n",
        )
        val store = store(dir)
        store.record(work())

        val rows = source(store, perf).rows()

        assertEquals(listOf(6 * HOUR_MS, 10 * HOUR_MS), rows.map { it.hour }, "the rebuilt hour reconciled")
        assertEquals(1L, rows.first().counts.turns, "the refusal is not a turn the rollup would have counted")
    }

    /** How far back the rebuild reaches is the window the install KEEPS, not the one a fresh install
     *  ships with: an upgraded install holding 90 days of records, or forever, rebuilt only 35. */
    @Test
    fun `the rebuild reaches as far back as the window the install keeps`(@TempDir dir: Path) {
        val day = 24 * HOUR_MS
        val now = 100 * day
        val perf = dir.resolve("head-perf.jsonl")
        // The oldest hour on disk is always left out (a generation can begin mid-hour), so the one
        // being asked about is the 39-day-old hour between it and the recorded hour.
        Files.writeString(
            perf,
            listOf(now - 40 * day, now - 39 * day, now).joinToString("\n") { rowAt(it) } + "\n",
        )
        // One window governs the store and the rebuild together, as ManagedHeadFactory wires them.
        val short = reaching(dir, perf, HistoryWindow(35), now)
        assertEquals(1, short.size, "a 35-day window has no gap to fill 39 days back")

        val kept = reaching(dir, perf, HistoryWindow(null), now)
        assertEquals(2, kept.size, "forever reaches the hour the turns are still on disk for")
        assertEquals((now - 39 * day) / HOUR_MS * HOUR_MS, kept.first().hour)
    }

    /** One head's economics as an install keeping [window] would read it. */
    private fun reaching(dir: Path, perf: Path, window: HistoryWindow, now: Long): List<EconomicsRow> {
        val store = EconomicsStore(
            dir.resolve("economics-${window.text}.json"),
            TurnPrice(card),
            WallClock { now },
            log = { },
            window = window,
        )
        store.record(work())
        return EconomicsStoreSource(
            store,
            PerfRowsFileSource(perf),
            TurnPrice(card),
            window = window,
            clock = WallClock { now },
        ).rows()
    }

    private fun EconomicsStoreSource.rows(): List<EconomicsRow> =
        assertInstanceOf(EconomicsRead.Rows::class.java, read()).rows

    private fun source(store: EconomicsStore, perf: Path) = EconomicsStoreSource(
        store,
        PerfRowsFileSource(perf),
        TurnPrice(card),
        window = HistoryWindow(1),
        clock = WallClock { NOW_MS },
    )

    private fun store(dir: Path) =
        EconomicsStore(dir.resolve("economics.json"), TurnPrice(card), WallClock { NOW_MS }, log = { })

    private fun work() = TurnEconomics(
        "synthetic",
        TurnTokens(5, 2, 1, 10),
        TurnBytes(100, 50),
        TurnTools(3, 2),
        history = UsageHistory(),
    )
}
