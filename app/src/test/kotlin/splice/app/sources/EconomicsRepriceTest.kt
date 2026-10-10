// An hour whose turns had no rate card when they ran reads at the card splice holds now, so installing a
// version that ships list prices prices the history it still holds, not only the turns that come after.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
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

private const val REPRICE_HOUR = 3_600_000L

/** One turn on `synthetic`, the same numbers the perf row below carries. */
private const val WORK_ROW =
    """{"ts":3600123,"model":"synthetic","outcome":"ok","req_bytes":100,"upstream_req_bytes":50,"tools_eager":3,""" +
        """"tools_deferred":2,"in_tokens":5,"cached_tokens":2,"cache_write_tokens":1,"out_tokens":10}"""

/** The activity side query, answered by the head with no model: a perf row marked as a local step, and no
 *  economics turn at all — TurnTelemetry.recordActivityAnswer writes the row and never calls the rollup. */
private const val ACTIVITY_ROW =
    """{"ts":3600200,"model":"synthetic","outcome":"ok","local_step":1,"activity_query":1,"req_bytes":1178082}"""

class EconomicsRepriceTest {
    private val card = ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(ModelEntry("synthetic", contextWindow = 1000, rates = ModelRates(3.0, 0.3, 15.0, 6.0))),
        defaultContextWindow = 1000,
    )

    @Test
    fun `an hour recorded with no card reads at the card splice holds now`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, WORK_ROW + "\n")
        val uncarded = store(dir, TurnPrice(null))
        uncarded.record(work())
        uncarded.flushNow()
        val recorded = Files.readString(dir.resolve("economics.json"))
        val asRecorded = EconomicsStoreSource(uncarded, PerfRowsFileSource(file)).rows().single()
        assertEquals(1L, asRecorded.cost.unpricedTurns, "with no card the turn was recorded unpriced")

        val carded = store(dir.resolve("carded"), TurnPrice(card))
        carded.record(work())
        val expected = EconomicsStoreSource(carded).rows().single()
        val repriced = EconomicsStoreSource(uncarded, PerfRowsFileSource(file), TurnPrice(card)).rows().single()

        assertEquals(expected.cost, repriced.cost, "the hour reads as if the card had been there all along")
        assertEquals(0L, repriced.cost.unpricedTurns)
        assertNotEquals(0.0, repriced.cost.costUsd)
        assertEquals(
            recorded,
            Files.readString(dir.resolve("economics.json")),
            "the reading never rewrites what the hour recorded",
        )
    }

    // Live, Oct 10: claudex's 03:00 CT hour held 256 side queries beside its 577 turns and read $0.00 with
    // every turn unpriced, because the hour rebuilt from the rows carried 714 local steps where the rollup
    // recorded 458. A row the rollup never counted cannot be what makes an hour a different hour.
    @Test
    fun `an hour that answered an activity side query is still the turns it counted`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, WORK_ROW + "\n" + ACTIVITY_ROW + "\n")
        val uncarded = store(dir, TurnPrice(null))
        uncarded.record(work())
        val repriced = EconomicsStoreSource(uncarded, PerfRowsFileSource(file), TurnPrice(card)).rows().single()
        assertEquals(0L, repriced.cost.unpricedTurns, "the side query is no turn of the rollup's to match")
        assertNotEquals(0.0, repriced.cost.costUsd)
    }

    @Test
    fun `an hour whose rows are not the turns it counted keeps the figure it recorded`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        // The hour's own row rotated away and a later turn's row stands in its place: not the same turns.
        Files.writeString(file, WORK_ROW.replace(""""in_tokens":5""", """"in_tokens":9""") + "\n")
        val uncarded = store(dir, TurnPrice(null))
        uncarded.record(work())
        val repriced = EconomicsStoreSource(uncarded, PerfRowsFileSource(file), TurnPrice(card)).rows().single()
        assertEquals(1L, repriced.cost.unpricedTurns, "the turn it could not re-derive is still uncounted")
        assertEquals(0.0, repriced.cost.costUsd)
    }

    @Test
    fun `an hour written before the cost field existed stays unknown rather than reading as priced`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, WORK_ROW + "\n")
        Files.writeString(
            dir.resolve("economics.json"),
            """[{"hour":3600000,"turns":1,"req_bytes":100,"upstream_req_bytes":50,"tools_eager":3,""" +
                """"tools_deferred":2,"deferral_turns":1,"in_tokens":5,"cached_tokens":2,""" +
                """"cache_write_tokens":1,"out_tokens":10}]""",
        )
        val row = EconomicsStoreSource(store(dir, TurnPrice(null)), PerfRowsFileSource(file), TurnPrice(card))
            .rows().single()
        assertNull(row.cost.costUsd, "no unpriced count to re-derive from: the hour's cost stays unknown")
    }

    private fun EconomicsStoreSource.rows(): List<EconomicsRow> =
        assertInstanceOf(EconomicsRead.Rows::class.java, read()).rows

    private fun store(dir: Path, price: TurnPrice) =
        EconomicsStore(dir.resolve("economics.json"), price, WallClock { REPRICE_HOUR + 123 }, log = { })

    private fun work() = TurnEconomics(
        "synthetic",
        TurnTokens(5, 2, 1, 10),
        TurnBytes(100, 50),
        TurnTools(3, 2),
        rateLimited = true,
        history = UsageHistory(),
    )
}
