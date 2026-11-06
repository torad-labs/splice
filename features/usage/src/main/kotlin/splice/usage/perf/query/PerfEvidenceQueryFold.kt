package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTags
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObjectiveVerdict
import splice.core.perf.observation.PerfObjectives
import splice.core.perf.observation.PerfObservation
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfEvidenceRead
import splice.usage.perf.query.PerfEvidenceJson.timelineEvent
import splice.usage.perf.query.PerfEvidenceJson.unconfiguredGoodput
import splice.usage.perf.query.PerfEvidenceWindow.inside
import splice.usage.perf.query.PerfQueryJson.numeric
import splice.usage.perf.query.PerfQueryJson.textValue

/** Folds complete bounded evidence reads by request identity; event counts never become request populations. */
internal class PerfEvidenceQueryFold(private val sources: PerfQuerySources) {
    fun read(query: PerfQuery, filter: PerfQueryFilter, nowMs: Long, data: QueryData) {
        val source = sources.evidence() ?: return missing(data)
        val through = maxOf(query.untilMs, nowMs)
        source.read(PerfEvidenceQuery(query.fromMs, through, query.filters["head"])).use { read ->
            data.evidenceAvailable = true
            data.asOf = read.asOfMs
            read.coverage.issues.forEach { data.issue(it.code, it.source) }
            if (!read.complete) data.issue("request_population_partial", EVIDENCE)
            val events = read.observations.distinctBy { it.stamp.observationId }
            if (events.size > MAX_EVENTS) {
                data.truncated = true
                data.issue("evidence_fold_limit", "query")
                return
            }
            fold(events, read, query, filter, data)
        }
    }

    private fun fold(
        events: List<PerfObservation>,
        read: PerfEvidenceRead,
        query: PerfQuery,
        filter: PerfQueryFilter,
        data: QueryData,
    ) {
        val requests = events.filter { it.links.requestId != null }.groupBy { it.links.requestId }
        val selected = requests.values.filter { matches(it, filter) }
        val complete = read.complete && !data.truncated && read.coverage.observedMs >= query.untilMs - query.fromMs
        if (!complete) data.issue("request_wall_coverage_unknown", EVIDENCE)
        data.populationCoverage["request_scope_resolved_at_terminal_or_latest_checkpoint"] = PerfQueryJson.coverageJson(
            read.coverage.copy(requestedUntilMs = query.untilMs),
            complete,
        )
        mergePoints(query, selected, complete, data)
        data.identities.addAll(selected.flatten().map { it.stamp.identity })
        data.counts = counts(selected, query, complete)
        data.goodput = goodput(selected, query, complete)
        data.timeline = timeline(selected, query)
        data.coverage = data.coverage.copy(
            observedMs = minOf(read.coverage.observedMs, query.untilMs - query.fromMs),
        )
        data.complete = complete && data.coverage.issues.isEmpty()
        if (selected.any { request -> request.none { it.kind == PerfEventKind.REQUEST_ARRIVED } }) {
            data.issue("arrival_counterpart_unknown", EVIDENCE)
        }
    }

    private fun mergePoints(
        query: PerfQuery,
        selected: List<List<PerfObservation>>,
        complete: Boolean,
        data: QueryData,
    ) {
        val points = EvidencePoints(query)
        selected.forEach(points::add)
        if (complete || data.points.isEmpty()) {
            data.populationSource = "request_scope_resolved_at_terminal_or_latest_checkpoint"
            data.points.entries.removeAll { it.key.metric in points.supported }
            data.points.putAll(points.points())
        } else {
            data.issue("rates_use_aggregate_population", "aggregates")
        }
    }

    private fun missing(data: QueryData) {
        data.missing.add("exact_request_evidence")
        data.issue("exact_request_evidence_unavailable", EVIDENCE)
    }

    private fun matches(events: List<PerfObservation>, filter: PerfQueryFilter): Boolean {
        val representative = events.firstOrNull { it.kind == PerfEventKind.REQUEST_FINISHED }
            ?: events.lastOrNull { it.kind == PerfEventKind.CHECKPOINT }
            ?: events.firstOrNull { it.kind == PerfEventKind.REQUEST_ARRIVED } ?: events.first()
        val scope = representative.scope.copy(outcome = representative.outcome ?: representative.scope.outcome)
        return filter.scope(scope) && filter.links(scope, representative.links)
    }

    private fun counts(
        requests: List<List<PerfObservation>>,
        query: PerfQuery,
        complete: Boolean,
    ): JsonObject = buildJsonObject {
        val arrivals = requests.count { group ->
            group.any {
                it.kind == PerfEventKind.REQUEST_ARRIVED && inside(
                    it,
                    query,
                )
            }
        }
        val terminals = requests.mapNotNull { group ->
            group.firstOrNull {
                it.kind == PerfEventKind.REQUEST_FINISHED && inside(it, query)
            }
        }
        put("record_unit", "request")
        put("known_arrivals", arrivals)
        put("known_completions", terminals.size)
        put("known_successful_completions", terminals.count { OutcomeTags.isClean(it.outcome ?: "?") })
        put("population_complete", complete)
        put("arrivals", numeric(arrivals.takeIf { complete }))
        put("completions", numeric(terminals.size.takeIf { complete }))
        put("attribution", "arrival_and_terminal_windows_separately")
        put("filter_attribution", "request_scope_resolved_at_terminal_or_latest_checkpoint")
    }

    private fun goodput(requests: List<List<PerfObservation>>, query: PerfQuery, complete: Boolean): JsonObject {
        val objective = query.objective ?: return unconfiguredGoodput()
        val verdicts = requests.filter { group ->
            group.any {
                it.kind == PerfEventKind.REQUEST_ARRIVED && inside(
                    it,
                    query,
                )
            }
        }
            .map { group ->
                val terminal = group.firstOrNull { it.kind == PerfEventKind.REQUEST_FINISHED }
                if (!complete && terminal == null) {
                    PerfObjectiveVerdict.UNKNOWN
                } else {
                    PerfObjectives.evaluate(terminal?.outcome, terminal?.values.orEmpty(), objective)
                }
            }
        val good = verdicts.count { it == PerfObjectiveVerdict.GOOD }
        val bad = verdicts.count { it == PerfObjectiveVerdict.BAD }
        return buildJsonObject {
            put("status", if (complete) "configured" else "partial")
            put("objective_id", objective.id)
            put("objective_revision", objective.revision)
            put("attribution", "arrivals_in_requested_window; followed_through_as_of")
            put("eligible", numeric(verdicts.size.takeIf { complete }))
            put("known_eligible", verdicts.size)
            put("known_evaluable", good + bad)
            put("observed_arrivals", verdicts.size)
            put("good", good)
            put("bad", bad)
            put("pending", verdicts.count { it == PerfObjectiveVerdict.PENDING })
            put("unknown", verdicts.count { it == PerfObjectiveVerdict.UNKNOWN })
            put("per_s", numeric(if (complete) good / ((query.untilMs - query.fromMs) / MILLIS_PER_SECOND) else null))
            put("known_good_per_s", good / ((query.untilMs - query.fromMs) / MILLIS_PER_SECOND))
            put("known_good_rate_is_lower_bound", !complete)
            put("good_fraction", numeric(if (complete && good + bad > 0) good.toDouble() / (good + bad) else null))
            put("population_complete", complete)
        }
    }

    private fun timeline(requests: List<List<PerfObservation>>, query: PerfQuery): JsonObject = buildJsonObject {
        val selected = requests.filter { group -> group.any { inside(it, query) } }.take(TIMELINE_REQUESTS)
        put("truncated", selected.size < requests.size)
        put(
            "requests",
            JsonArray(
                selected.map { group ->
                    buildJsonObject {
                        put("request_id", textValue(group.first().links.requestId))
                        put("events", JsonArray(group.sortedBy { it.stamp.monotonicMs }.map(::timelineEvent)))
                    }
                },
            ),
        )
    }
}

internal object PerfEvidenceWindow {
    fun inside(
        event: PerfObservation,
        query: PerfQuery,
    ): Boolean = event.stamp.wallMs >= query.fromMs && event.stamp.wallMs < query.untilMs
}

private const val MAX_EVENTS = 4_096
private const val TIMELINE_REQUESTS = 20

private const val EVIDENCE = "evidence"
