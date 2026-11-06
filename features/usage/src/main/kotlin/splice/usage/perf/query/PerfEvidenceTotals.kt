package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTags
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObjectiveVerdict
import splice.core.perf.observation.PerfObjectives
import splice.core.perf.observation.PerfObservation
import splice.usage.perf.query.PerfEvidenceWindow.inside
import splice.usage.perf.query.PerfQueryJson.numeric

internal class PerfEvidenceTotals(private val query: PerfQuery) {
    private var arrivals = 0L
    private var completions = 0L
    private var successful = 0L
    private var unclosed = 0L
    private val verdicts = LongArray(PerfObjectiveVerdict.entries.size)

    fun add(events: List<PerfObservation>) {
        val terminal = events.firstOrNull { it.kind == PerfEventKind.REQUEST_FINISHED }
        if (terminal != null && inside(terminal, query)) {
            completions++
            if (OutcomeTags.isClean(terminal.outcome ?: "?")) successful++
        }
        if (events.any { it.kind == PerfEventKind.REQUEST_ARRIVED && inside(it, query) }) {
            arrivals++
            if (terminal == null) unclosed++
            query.objective?.let {
                verdicts[PerfObjectives.evaluate(terminal?.outcome, terminal?.values.orEmpty(), it).ordinal]++
            }
        }
    }

    fun counts(complete: Boolean): JsonObject = buildJsonObject {
        put("record_unit", "request")
        put("known_arrivals", arrivals)
        put("known_completions", completions)
        put("known_successful_completions", successful)
        put("population_complete", complete)
        put("arrivals", numeric(arrivals.takeIf { complete }))
        put("completions", numeric(completions.takeIf { complete }))
        put("attribution", "arrival_and_terminal_windows_separately")
        put("filter_attribution", "request_scope_resolved_at_terminal_or_latest_checkpoint")
    }

    fun goodput(complete: Boolean): JsonObject {
        val objective = query.objective ?: return PerfEvidenceJson.unconfiguredGoodput()
        val good = verdicts[PerfObjectiveVerdict.GOOD.ordinal]
        val bad = verdicts[PerfObjectiveVerdict.BAD.ordinal]
        val pending = verdicts[PerfObjectiveVerdict.PENDING.ordinal]
        val unknown = verdicts[PerfObjectiveVerdict.UNKNOWN.ordinal]
        val seconds = (query.untilMs - query.fromMs) / MILLIS_PER_SECOND
        return buildJsonObject {
            put("status", if (complete) "configured" else "partial")
            put("objective_id", objective.id)
            put("objective_revision", objective.revision)
            put("attribution", "arrivals_in_requested_window; followed_through_as_of")
            put("eligible", numeric(arrivals.takeIf { complete }))
            put("known_eligible", arrivals)
            put("known_evaluable", good + bad)
            put("observed_arrivals", arrivals)
            put("good", good)
            put("bad", bad)
            put("pending", pending - if (complete) 0 else unclosed)
            put("unknown", unknown + if (complete) 0 else unclosed)
            put("per_s", numeric(if (complete) good / seconds else null))
            put("known_good_per_s", good / seconds)
            put("known_good_rate_is_lower_bound", !complete)
            put("good_fraction", numeric(if (complete && good + bad > 0) good.toDouble() / (good + bad) else null))
            put("population_complete", complete)
        }
    }
}
