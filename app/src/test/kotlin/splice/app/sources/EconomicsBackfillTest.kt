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
