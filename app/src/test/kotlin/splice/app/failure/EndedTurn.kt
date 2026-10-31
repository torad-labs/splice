// NEW: failure-handling slice 1. What a scripted head recorded for a turn that ended: its perf row and the usage
// console's figures over the head's rows, read through GET /api/perf/turns the way the console reads them.
package splice.app.failure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import splice.app.sources.PerfRowsFileSource
import splice.app.sources.PerfTurnsRoute
import splice.core.util.WallClock
import splice.head.awaitPerfRow
import splice.head.perfRows
import splice.head.scripted.ScriptedHead
import splice.usage.UsageHead
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

/** How long a turn's row may take to land after its answer ended: it is written on a server thread with no signal. */
private const val ROW_WAIT_MS = 30_000L

/** How far past the newest row the route's clock reads, so every row is in its window. */
private const val ROUTE_LEAD_MS = 60_000L

/** The most rows a figure is counted over. */
private const val ROWS_ASKED = 50

/** The perf row a turn wrote, read back from the head's JSONL file. */
internal class EndedTurn(private val row: JsonObject) {
    /** The row's outcome tag. */
    val outcome: String? get() = text("outcome")

    /** The row's cause name, null when the ending wrote none. */
    val cause: String? get() = text("cause")

    private fun text(key: String): String? = (row[key] as? JsonPrimitive)?.contentOrNull
}

/** Reads what [head] recorded. */
internal class HeadRecords(private val head: ScriptedHead) {
    /** The newest row whose outcome is [outcome], or the newest row when [outcome] is null; waits for it to land. */
    fun ended(outcome: String? = null): EndedTurn {
        val line = awaitPerfRow(head.perfFile, timeoutMs = ROW_WAIT_MS) { line ->
            outcome == null || parse(line)["outcome"]?.let { (it as? JsonPrimitive)?.contentOrNull } == outcome
        }
        return EndedTurn(parse(checkNotNull(line) { "no perf row${outcome?.let { " with outcome $it" }.orEmpty()}" }))
    }

    /** How many of the head's rows the usage console counts under the figure the route names [test]: "cutoff" is
     *  Cut off and "gaveup" is Given up (TurnsTest). */
    fun figure(test: String): Int {
        val newest = perfRows(head.perfFile).mapNotNull { (parse(it)["ts"] as? JsonPrimitive)?.longOrNull }.maxOrNull()
        val clock = WallClock { (newest ?: 0L) + ROUTE_LEAD_MS }
        val turns = PerfTurnsRoute(usageHead(), clock).turns(0, ROWS_ASKED, mapOf("test" to test))
        return checkNotNull((turns["count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()) { "no count in $turns" }
    }

    private fun usageHead(): UsageHead = UsageHead(
        key = USAGE_KEY,
        label = USAGE_KEY,
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = WARN_PCT, warnTokens5h = 0),
        sinks = UsageHeadSinks(perfRows = PerfRowsFileSource(head.perfFile)),
    )

    private fun parse(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject
}

/** The key the usage console knows the scripted head by. */
private const val USAGE_KEY = "scripted"

/** A warning threshold no test reaches. */
private const val WARN_PCT = 80
