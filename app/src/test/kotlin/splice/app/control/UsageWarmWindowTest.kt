// Oct 25, 2026 — a start prepares each head's requests for what the console asks: Usage's seven local days, Requests 7d
// and the 7d summary. The warm read from the oldest hourly total, a month of archives on the two big heads, and 24
// hours on a head with no totals, so a head without a week of totals answered its first Usage open pending. An ask
// older than the week is read when it is made, and comes back whole. Synthetic rows only, written here.
package splice.app.control

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.mount.UsageReadWarmup
import splice.app.sources.PerfRowsFileSource
import splice.core.perf.PerfArchiveName
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.Ticker
import splice.usage.USAGE_READ_PENDING_HEADER
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads
import splice.usage.economics.EconomicsBytes
import splice.usage.economics.EconomicsCost
import splice.usage.economics.EconomicsRead
import splice.usage.economics.EconomicsRow
import splice.usage.economics.EconomicsTokens
import splice.usage.economics.EconomicsTools
import splice.usage.economics.EconomicsTurnCounts
import splice.usage.economics.HeadEconomicsSource
import splice.usage.perf.PerfRoutes
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

private val CHICAGO: ZoneId = ZoneId.of("America/Chicago")

// The last minute of the Saturday after a fall-back Sunday: Usage's week starts at local midnight on Nov 1, before the
// clocks went back an hour, so it reaches 7 days and 59 minutes behind this.
private val NOW: Long = ZonedDateTime.of(2026, 11, 7, 23, 59, 0, 0, CHICAGO).toInstant().toEpochMilli()
private val USAGE_WEEK_START: Long = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, CHICAGO).toInstant().toEpochMilli()

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS
private const val WEEK_MS = 7 * DAY_MS
private const val ROW_SPACING_MS = 5 * 60_000L
private const val GENERATION_DAYS = 2
private const val WAIT_MS = 30_000L

// A head with no hourly totals, one whose totals began three days ago, and one with a month of totals over 35 days of
// request log.
private const val QUIET = "quiet"
private const val RECENT = "recent"
private const val MONTHLY = "monthly"

class UsageWarmWindowTest {
    @Test
    fun `every head answers the console's week at once after start, a fall-back week included`(@TempDir dir: Path) {
        val fixture = Fixture(dir)
        fixture.started {
            for (head in fixture.heads) {
                for (since in listOf(NOW - WEEK_MS, USAGE_WEEK_START)) {
                    val answer = fixture.turns(head.key, since)
                    assertFalse(answer.pending(), "${head.key} answers since $since at once after its warm: $answer")
                    assertEquals(fixture.written(head.key, since), answer.count(), "${head.key} since $since")
                }
            }
        }
    }

    @Test
    fun `an ask older than the week is read when it is made and comes back whole`(@TempDir dir: Path) {
        val fixture = Fixture(dir)
        fixture.started {
            val since = NOW - 20 * DAY_MS
            val first = fixture.turns(MONTHLY, since)
            assertTrue(first.pending(), "a start prepares the week, not 20 days: $first")
            assertFalse(first.containsKey("count"), "an unread window is no invented count")
            fixture.settled()
            val finished = fixture.turns(MONTHLY, since)
            assertFalse(finished.pending(), "the read the ask started answers the next ask: $finished")
            assertEquals(fixture.written(MONTHLY, since), finished.count(), "every row since the older cutoff")
            val week = fixture.turns(MONTHLY, NOW - WEEK_MS)
            assertFalse(week.pending(), "the week stays ready after the older read: $week")
            assertEquals(fixture.written(MONTHLY, NOW - WEEK_MS), week.count())
        }
    }

    private fun JsonObject.pending(): Boolean = this["read_pending"]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.count(): Long = getValue("count").jsonPrimitive.content.toLong()

    /** A command whose request log holds [days] of rows and whose hourly totals begin [totalsDays] back, or none. */
    private data class HeadShape(val key: String, val days: Int, val totalsDays: Int?)

    /** The daemon's turns route and its startup warm over three real file sources, with the warm's clock at NOW. */
    private inner class Fixture(dir: Path) {
        private val shapes = listOf(
            HeadShape(QUIET, days = 10, totalsDays = null),
            HeadShape(RECENT, days = 10, totalsDays = 3),
            HeadShape(MONTHLY, days = 35, totalsDays = 30),
        )
        private val rows = shapes.associate { it.key to history(dir, it) }
        val heads: List<UsageHead> = shapes.map { head(dir, it) }
        private val logged = CopyOnWriteArrayList<String>()
        private val reads = SupervisorJob()
        private val scope = CoroutineScope(reads + Dispatchers.IO)
        private val warmup = UsageReadWarmup(
            scope,
            UsageHeads { heads },
            Dispatchers.IO,
            clock = WallClock { NOW },
            ticker = Ticker { false },
            log = LogSink { logged += it },
        )
        private val routes = PerfRoutes(UsageHeadLookup { name -> heads.filter { it.key == name } }, WallClock { NOW })
            .apply { preparation = warmup }

        /** Starts the warm as the daemon's start does, waits until the reads it started have finished, then runs
         *  [checks]. */
        fun started(checks: () -> Unit) {
            try {
                warmup.start()
                settled()
                for (head in heads) {
                    check(logged.any { it.startsWith("[${head.key}][usage] requests read") }) {
                        "${head.key} never finished its warm: $logged"
                    }
                }
                checks()
            } finally {
                scope.cancel()
            }
        }

        /** Waits for every read the warm has started so far to finish: each is a child of this fixture's scope. */
        fun settled() {
            runBlocking { withTimeout(WAIT_MS) { reads.children.toList().joinAll() } }
        }

        /** The head's entry of one GET /api/perf/turns the console sends: opted in to pending answers, n=1. */
        fun turns(key: String, since: Long): JsonObject = runBlocking {
            var body = ""
            testApplication {
                application { routing { get("/api/perf/turns") { routes.turns(call) } } }
                body = client.get("/api/perf/turns?head=$key&n=1&since=$since&until=${NOW + 1}") {
                    header(USAGE_READ_PENDING_HEADER, "1")
                }.bodyAsText()
            }
            Json.parseToJsonElement(body).jsonObject.getValue("heads").jsonArray.single().jsonObject
        }

        /** The rows this test wrote for [key] from [since] up to the ask's until. */
        fun written(key: String, since: Long): Long = rows.getValue(key).count { it >= since && it <= NOW }.toLong()

        /** Rows [ROW_SPACING_MS] apart back [HeadShape.days] from NOW, oldest first, in generations of two days: the
         *  newest is the live file, the one before it `.1`, and the rest archives under PerfArchiveName, each named
         *  for the rotation that archived it (the first row two generations later). */
        private fun history(dir: Path, shape: HeadShape): List<Long> {
            val count = (shape.days * DAY_MS / ROW_SPACING_MS).toInt()
            val stamps = (count - 1 downTo 0).map { NOW - it * ROW_SPACING_MS }
            val generations = stamps.chunked((GENERATION_DAYS * DAY_MS / ROW_SPACING_MS).toInt())
            val file = dir.resolve("${shape.key}-perf.jsonl")
            val archive = Files.createDirectories(dir.resolve("${shape.key}-archive"))
            val name = PerfArchiveName(file.fileName.toString())
            generations.forEachIndexed { index, generation ->
                val target = when (index) {
                    generations.lastIndex -> file
                    generations.lastIndex - 1 -> file.resolveSibling("${file.fileName}.1")
                    else -> archive.resolve(name.of(generations[index + 2].first()))
                }
                Files.write(target, generation.map(::line))
            }
            return stamps
        }

        private fun line(ts: Long): String =
            """{"ts":$ts,"outcome":"ok","model":"synthetic-model","in_tokens":12,"out_tokens":3}"""

        private fun head(dir: Path, shape: HeadShape): UsageHead {
            val hours = shape.totalsDays?.let { days ->
                (days downTo 0).map { (NOW - it * DAY_MS) / HOUR_MS * HOUR_MS }
            }
            return UsageHead(
                shape.key,
                "claude-${shape.key}",
                HeadUsageSource { UsageView(0, 0, null) },
                warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
                sinks = UsageHeadSinks(
                    perfRows = PerfRowsFileSource(
                        dir.resolve("${shape.key}-perf.jsonl"),
                        dir.resolve("${shape.key}-archive"),
                    ),
                    economics = HeadEconomicsSource { EconomicsRead.Rows(hours.orEmpty().map(::hourly)) },
                ),
            )
        }

        private fun hourly(hour: Long) = EconomicsRow(
            hour = hour,
            counts = EconomicsTurnCounts(turns = 12),
            tokens = EconomicsTokens(inTokens = 144, cachedTokens = 0, cacheWriteTokens = 0, outTokens = 36),
            bytes = EconomicsBytes(reqBytes = 0, upstreamBytes = 0),
            tools = EconomicsTools(toolsEager = 0, toolsDeferred = 0, deferralTurns = 0),
            rateLimited = 0,
            cost = EconomicsCost(costUsd = 0.0, unpricedTurns = 0),
        )
    }
}
