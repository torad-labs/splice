// ONE RULE FOR BOTH STORES, found in review on Oct 10, 2026 and pinned here.
//
// The hourly rollup's unit is a whole UTC hour and can only be dropped whole. The request records
// are cut row by row. ProbeEconomics reconciles the two on every read of Usage, and answers
// Unavailable for a head's WHOLE hourly history — today's spend included — when a bucket is not
// accounted for by the records beside it.
//
// So a cut finer than an hour breaks them against each other. Kolkata is UTC+5:30, so "today only"
// cuts at 18:30 UTC, inside the bucket that opened at 18:00: a minute-exact cut took that bucket's
// earlier records and left the bucket. Nothing in either store is wrong on its own, and the person
// loses their hourly history anyway. The fix is the grain, in HistoryWindow.onTheHour, which every
// reader of the window goes through; this test is what says the two stores still agree afterwards.
package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.model.TurnPrice
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.core.turn.UsageHistory
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.usage.EconomicsStore
import splice.head.usage.TurnBytes
import splice.head.usage.TurnEconomics
import splice.head.usage.TurnTokens
import splice.head.usage.TurnTools
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime

/** UTC+5:30, so the operator's midnight lands at 18:30 UTC: half an hour inside a bucket. */
private val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")

private const val AN_HOUR = 60 * 60 * 1000L

private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

private fun record(ts: Long): String = """{"ts":$ts,"model":"m","outcome":"ok","in_tokens":7}"""

class HistoryCutGrainTest {

    /** 02:00 UTC on Oct 10, which is 07:30 that morning in Kolkata: the person's day began at 18:30
     *  the previous evening UTC, and the hour it began inside opened at 18:00. */
    private val now = utc(2026, 10, 10, 2, 0)
    private val yesterdayEvening = utc(2026, 10, 9, 17, 50)
    private val beforeMidnight = utc(2026, 10, 9, 18, 10)
    private val afterMidnight = utc(2026, 10, 9, 18, 50)
    private val thisMorning = utc(2026, 10, 10, 1, 0)

    @Test
    fun `a save cuts the hourly totals and the records they are graded against by one rule`(
        @TempDir tmp: Path,
    ) {
        val paths = StatePaths(baseOverride = tmp.resolve("state")).also { Files.createDirectories(it.perfArchiveDir) }
        val stamps = listOf(yesterdayEvening, beforeMidnight, afterMidnight, thisMorning)
        Files.writeString(paths.perfStatsFile("h"), stamps.joinToString("\n", transform = ::record) + "\n")
        val hours = rollupOf(tmp, stamps)
        val routes = HistoryRoutes(
            paths,
            HistoryWindowStore { null },
            HistoryStores { moment ->
                hours.trimBefore(moment)
                null
            },
            WallClock { now },
            KOLKATA,
            LogSink { },
        )

        // The moment the person is shown, and then the save they press, exactly as the console does.
        val shown = cutoffOf(routes.read(HistoryWindow(35, KOLKATA), proposedDays = "0").body)
        routes.save("""{"days":0,"delete_before_epoch_ms":$shown}""")

        assertEquals(
            utc(2026, 10, 9, 18, 0),
            shown,
            "the cut is the hour the person's midnight falls inside, not the half hour into it",
        )
        val left = Files.readAllLines(paths.perfStatsFile("h")).map(::stampOf)
        assertEquals(
            listOf(beforeMidnight, afterMidnight, thisMorning),
            left,
            "both records of the hour that holds their midnight are still there: it goes whole or not at all",
        )
        assertEquals(
            left.groupingBy { it / AN_HOUR * AN_HOUR }.eachCount().mapValues { it.value.toLong() },
            hours.read().associate { it.hour to it.turns },
            "every hour the rollup still holds is accounted for by the records it is reconciled against",
        )
    }

    /** The head's hourly rollup, one turn recorded in each of [stamps]' own hours, under the same
     *  window the wire cuts by — so the store's own trim has already run at the clock the save
     *  arrives at, exactly as a running daemon's has. */
    private fun rollupOf(tmp: Path, stamps: List<Long>): EconomicsStore {
        var at = stamps.first()
        val store = EconomicsStore(
            tmp.resolve("economics.json"),
            TurnPrice(null),
            WallClock { at },
            log = { },
            kept = KeptHistory { HistoryWindow(0, KOLKATA) },
        )
        for (stamp in stamps) {
            at = stamp
            store.record(
                TurnEconomics(
                    model = "m",
                    tokens = TurnTokens(inTokens = 7, cachedTokens = 0, cacheWriteTokens = 0, outTokens = 0),
                    bytes = TurnBytes(reqBytes = null, upstreamBytes = null),
                    tools = TurnTools(toolsEager = null, toolsDeferred = null),
                    history = UsageHistory(),
                ),
            )
        }
        return store
    }

    private fun stampOf(line: String): Long =
        Json.parseToJsonElement(line).jsonObject.getValue("ts").jsonPrimitive.long

    /** The cut moment out of a reply body, where the console reads it. */
    private fun cutoffOf(body: String): Long = Json.parseToJsonElement(body)
        .jsonObject.getValue("cut").jsonObject.getValue("cutoff_epoch_ms").jsonPrimitive.long
}
