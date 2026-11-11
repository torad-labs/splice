package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.usage.perf.query.PerfQueryJson.envelope

/**
 * The series reply at the narrowest interval that fits a reply: bounded resolution, as the instrumentation plan
 * names it.
 *
 * Until Nov 11, 2026 every entry repeated the reply's whole coverage, about 1,000 characters each on a desk with
 * eleven issues to report, and a reply over the budget was refused whole. Any period past twelve minutes answered
 * result_limit there, and the console's History tab drew nothing. An entry now holds its interval, scope and
 * metrics, and when the entries are still too many their intervals double in width until the reply fits.
 */
internal class PerfSeriesReply(private val query: PerfQuery, private val data: QueryData) {
    private val span = query.untilMs - query.fromMs

    fun render(): JsonObject {
        var width = SERIES_FINEST_MS
        var reply = reply(width)
        while (reply.toString().length > QUERY_REPLY_CHARS && width < span) {
            width *= 2
            data.resolution = maxOf(data.resolution, minOf(width, span))
            reply = reply(width)
        }
        return reply
    }

    private fun reply(width: Long): JsonObject =
        envelope(query, data, buildJsonObject { put("series", PerfQueryMetrics.series(data, query.fromMs, width)) })
}

// The recorder's own interval: a series is never returned finer than the minute it was recorded at.
private const val SERIES_FINEST_MS = 60_000L
