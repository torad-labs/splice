// NEW: server mode (docs/specs/server-mode.md, F6). What the server counted of the requests it ended since the daemon
// started, per person and team, from each request's ledger row as it is written: requests by command and status
// class, tokens by kind, and the latency, speed and stall histograms. Nothing is read back from the ledger at boot, so
// every series starts at zero with the daemon; a person with a live key gets every series at zero before their first
// request (present), so a dashboard shows everyone.
package splice.server.metrics

import splice.core.server.MetricFamily
import splice.core.server.MetricLabels
import splice.core.server.MetricPoint
import splice.server.ledger.LedgerRow
import splice.server.ledger.Outcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

internal class TurnTallies {
    private val requests = ConcurrentHashMap<MetricLabels, LongAdder>()
    private val tokens = ConcurrentHashMap<MetricLabels, LongAdder>()
    private val histograms = TurnHistogram.entries.associateWith { ConcurrentHashMap<MetricLabels, Buckets>() }

    /** One request that ended as [row] says, its answer having stalled [stallMs] at most after its first word. */
    fun counted(row: LedgerRow, stallMs: Long?) {
        val team = row.team.orEmpty()
        tally(requests, requestLabels(row.person, team, row.command, statusClass(row.status))).increment()
        tally(tokens, tokenLabels(row.person, team, KIND_PROMPT)).add(row.promptTokens)
        tally(tokens, tokenLabels(row.person, team, KIND_CACHED)).add(row.cachedTokens)
        tally(tokens, tokenLabels(row.person, team, KIND_COMPLETION)).add(row.completionTokens)
        val who = whoLabels(row.person, team)
        if (row.outcome != Outcome.REFUSED.wire) observe(TurnHistogram.DURATION, who, seconds(row.durationMs))
        row.ttftMs?.let { observe(TurnHistogram.TTFT, who, seconds(it)) }
        row.prefillTps?.let { observe(TurnHistogram.PREFILL, who, it) }
        row.decodeTps?.let { observe(TurnHistogram.DECODE, who, it) }
        stallMs?.let { observe(TurnHistogram.STALL, who, seconds(it)) }
    }

    /** Makes sure [person], on [team] (empty for none), has every series, at zero where nothing was counted yet: a
     *  request series for each of [commands] and each status class, a token series of each kind, and each
     *  histogram. */
    fun present(person: String, team: String, commands: Collection<String>) {
        for (command in commands) STATUS_CLASSES.forEach { tally(requests, requestLabels(person, team, command, it)) }
        TOKEN_KINDS.forEach { tally(tokens, tokenLabels(person, team, it)) }
        val who = whoLabels(person, team)
        TurnHistogram.entries.forEach { histogram -> series(histogram, who) }
    }

    /** Every family's series of the people in [shown], in a stable order. */
    fun families(shown: Set<String>): List<MetricFamily> {
        val counters = listOf(
            MetricFamily.Counter(REQUESTS_METRIC, REQUESTS_HELP, UNIT_REQUESTS, points(requests, shown)),
            MetricFamily.Counter(TOKENS_METRIC, TOKENS_HELP, UNIT_TOKENS, points(tokens, shown)),
        )
        val histogramFamilies = TurnHistogram.entries.map { histogram ->
            val points = histograms.getValue(histogram).filterKeys { it.of(LABEL_PERSON) in shown }
                .toSortedMap(SERIES_ORDER).map { (labels, buckets) -> buckets.point(labels) }
            MetricFamily.Histogram(histogram.metric, histogram.help, histogram.unit, histogram.bounds, points)
        }
        return counters + histogramFamilies
    }

    private fun observe(histogram: TurnHistogram, who: MetricLabels, value: Double) {
        series(histogram, who).observe(value)
    }

    private fun series(histogram: TurnHistogram, who: MetricLabels): Buckets =
        histograms.getValue(histogram).computeIfAbsent(who) { Buckets(histogram.bounds) }

    private fun tally(map: ConcurrentHashMap<MetricLabels, LongAdder>, labels: MetricLabels): LongAdder =
        map.computeIfAbsent(labels) { LongAdder() }

    private fun points(map: Map<MetricLabels, LongAdder>, shown: Set<String>): List<MetricPoint> =
        map.filterKeys { it.of(LABEL_PERSON) in shown }.toSortedMap(SERIES_ORDER).map { (labels, count) ->
            MetricPoint(labels, count.sum().toDouble())
        }

    private fun requestLabels(person: String, team: String, command: String, statusClass: String) =
        MetricLabels(
            listOf(LABEL_PERSON to person, LABEL_TEAM to team, "command" to command, "status_class" to statusClass),
        )

    private fun tokenLabels(person: String, team: String, kind: String) =
        MetricLabels(listOf(LABEL_PERSON to person, LABEL_TEAM to team, "kind" to kind))

    private fun whoLabels(person: String, team: String) =
        MetricLabels(listOf(LABEL_PERSON to person, LABEL_TEAM to team))

    /** `2xx`, `4xx`, `5xx`: the first digit of [status] and two x's. */
    private fun statusClass(status: Int): String = "${status / STATUS_CLASS_DIVISOR}xx"

    private fun seconds(ms: Long): Double = ms.milliseconds.toDouble(DurationUnit.SECONDS)
}

/** The label every series carries first: whose requests they are. */
internal const val LABEL_PERSON: String = "person"

/** The person's team, empty when they are on none. */
internal const val LABEL_TEAM: String = "team"

private const val REQUESTS_METRIC = "splice_server_requests_total"
private const val REQUESTS_HELP = "Requests the server ended, by person, team, command and status class."

/** A count of requests, as both exports name its unit. */
internal const val UNIT_REQUESTS: String = "{request}"
private const val TOKENS_METRIC = "splice_server_tokens_total"
private const val TOKENS_HELP =
    "Tokens the provider billed, by person, team and kind: prompt (every prompt token), cached (the share of them " +
        "read from the prompt cache) and completion."
private const val UNIT_TOKENS = "{token}"
private const val KIND_PROMPT = "prompt"
private const val KIND_CACHED = "cached"
private const val KIND_COMPLETION = "completion"
private val TOKEN_KINDS = listOf(KIND_PROMPT, KIND_CACHED, KIND_COMPLETION)

// The status classes a person's request series start at zero in; a 1xx or 3xx is counted when one ends a request.
private val STATUS_CLASSES = listOf("2xx", "4xx", "5xx")

// why: a status's class is its hundreds digit, so a 200 and a 204 are both counted as 2xx.
private const val STATUS_CLASS_DIVISOR = 100

// Series in the order their labels read, so a scrape lists them the same way every time.
private val SERIES_ORDER: Comparator<MetricLabels> =
    compareBy { labels -> labels.pairs.joinToString("\u0000") { it.second } }
