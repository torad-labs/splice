package splice.usage.perf.query.reduction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.usage.perf.history.PerfEvidenceReceipt
import splice.usage.perf.history.PerfPartitionedObservationConsumer
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryFilter
import splice.usage.perf.query.PerfQueryJson
import splice.usage.perf.query.QueryData

internal class PerfRequestReduction(
    private val query: PerfQuery,
    private val filter: PerfQueryFilter,
    private val data: QueryData,
) : PerfPartitionedObservationConsumer {
    private val memory = PerfReductionMemory(PERF_REDUCTION_BYTES - TIMELINE_RESERVATION_BYTES)
    private val requests = LinkedHashMap<ReducedRequestKey, PerfReducedRequest>()
    private val timeline = PerfRepresentativeTimeline(PerfReductionMemory(TIMELINE_RESERVATION_BYTES))
    private val keepLinks = query.filters.keys.any { it in setOf("session", "project", "account") }
    private var membershipUnknown = false
    private val points = EvidencePoints(query)
    private val totals = PerfEvidenceTotals(query)
    private val workload = PerfWorkloadComparison(query)
    private var matching = 0L

    override fun accept(observation: PerfObservation): Boolean {
        val request = observation.links.requestId ?: return true
        val key = ReducedRequestKey(observation.stamp.identity.bootId, request)
        return if (observation.kind !in REDUCED_KINDS) {
            if (key in requests) capture(key, observation)
            true
        } else {
            acceptRequest(key, observation)
        }
    }

    private fun acceptRequest(key: ReducedRequestKey, event: PerfObservation): Boolean {
        val input = intern(event) ?: return limit()
        val state = requests[key] ?: newRequest(key, input.identity) ?: return limit()
        return when {
            state.identity != input.identity -> limit("request_identity_conflict")
            !state.accept(event.copy(outcome = input.outcome), input.scope, input.links) -> limit()
            else -> { capture(key, event); true }
        }
    }

    private fun intern(event: PerfObservation): ReducedInput? {
        val outcome = event.outcome?.let(memory::text)
        if (event.outcome != null && outcome == null) return null
        return memory.identity(event.stamp.identity)?.let { identity ->
            memory.scope(event.scope)?.let { scope ->
                val links = if (keepLinks) memory.links(event.links) else PerfReducedValues.emptyLinks
                links?.let { ReducedInput(identity, scope, it, outcome) }
            }
        }
    }

    private fun capture(key: ReducedRequestKey, observation: PerfObservation) {
        val state = requests[key] ?: return
        if (PerfEvidenceWindow.inside(observation, query)) state.hasRequestedEvidence = true
        if (!state.hasRequestedEvidence) return
        if (timeline.contains(key) || PerfEvidenceSelection.matches(listOf(observation), filter)) {
            timeline.capture(key, observation)
        }
    }

    private fun newRequest(key: ReducedRequestKey, identity: PerfIdentity): PerfReducedRequest? {
        if (!memory.take(REQUEST_FIXED_BYTES + key.request.length * 2L)) return null
        val normalized = key.copy(boot = identity.bootId)
        return PerfReducedRequest(key.request, identity, memory).also { requests[normalized] = it }
    }

    private fun limit(code: String = "request_state_limit"): Boolean {
        data.truncated = true
        data.issue(code, "query")
        return false
    }

    fun finish(receipt: PerfEvidenceReceipt) {
        partitionFinished()
        val complete = listOf(
            receipt.complete,
            !data.truncated,
            !points.truncated,
            !membershipUnknown,
            receipt.coverage.observedMs >= query.untilMs - query.fromMs,
        ).all {
            it
        }
        apply(receipt, complete)
        data.counts = totals.counts(complete)
        data.goodput = totals.goodput(complete)
        data.workload = JsonObject(workload.render(complete) + receiptMetadata(receipt))
        data.timeline = timeline.render(matching)
        merge(points, complete)
    }

    override fun partitionFinished() {
        matching += reduce(points, totals, workload)
        memory.nextPartition()
    }

    private fun reduce(points: EvidencePoints, totals: PerfEvidenceTotals, workload: PerfWorkloadComparison): Long {
        var matching = 0L
        val iterator = requests.entries.iterator()
        while (iterator.hasNext()) {
            val (key, state) = iterator.next()
            val events = state.events()
            if (events.isNotEmpty() && PerfEvidenceMembership.unknown(events, query, filter)) membershipUnknown = true
            val selected = state.hasRequestedEvidence && PerfEvidenceSelection.matches(events, filter)
            workload.add(events, selected)
            if (selected) {
                matching++
                points.add(events); totals.add(events); timeline.select(key)
                data.identities.add(state.identity)
                if (events.none { it.kind == PerfEventKind.REQUEST_ARRIVED }) {
                    data.issue(
                        "arrival_counterpart_unknown",
                        EVIDENCE,
                    )
                }
            }
            iterator.remove()
        }
        requests.clear()
        return matching
    }

    private fun apply(receipt: PerfEvidenceReceipt, complete: Boolean) {
        data.evidenceAvailable = true
        data.asOf = receipt.asOfMs
        receipt.coverage.issues.forEach { data.issue(it.code, it.source) }
        if (!receipt.complete) data.issue("request_population_partial", EVIDENCE)
        if (!complete) data.issue("request_wall_coverage_unknown", EVIDENCE)
        if (membershipUnknown) data.issue("request_cohort_membership_unknown", EVIDENCE)
        val coverage = receipt.coverage.copy(
            requestedUntilMs = query.untilMs,
            observedMs = minOf(receipt.coverage.observedMs, query.untilMs - query.fromMs),
        )
        data.populationCoverage["request_scope_resolved_at_terminal_or_latest_checkpoint"] = JsonObject(
            PerfQueryJson.coverageJson(coverage, complete) + receiptMetadata(receipt),
        )
        data.coverage = data.coverage.copy(observedMs = coverage.observedMs)
        data.complete = complete && data.coverage.issues.isEmpty()
    }

    private fun merge(points: EvidencePoints, complete: Boolean) {
        if (points.truncated) data.issue("request_point_limit", "query")
        data.resolution = maxOf(data.resolution, points.resolutionMs)
        if (complete || data.points.isEmpty()) {
            data.populationSource = "request_scope_resolved_at_terminal_or_latest_checkpoint"
            data.points.entries.removeAll { it.key.metric in points.supported }
            data.points.putAll(points.points())
        } else {
            data.issue("rates_use_aggregate_population", "aggregates")
        }
    }

    private fun receiptMetadata(receipt: PerfEvidenceReceipt): JsonObject = buildJsonObject {
        put("as_of_ms", receipt.asOfMs)
        put("coverage_basis", receipt.coverageBasis)
        put("event_time_final", receipt.eventTimeFinal)
    }
}

internal object PerfEvidenceSelection {
    fun matches(events: List<PerfObservation>, filter: PerfQueryFilter): Boolean {
        val representative = representative(events)
        val scope = representative.scope.copy(outcome = representative.outcome ?: representative.scope.outcome)
        return filter.scope(scope) && filter.links(scope, representative.links)
    }

    fun representative(events: List<PerfObservation>): PerfObservation =
        events.firstOrNull { it.kind == PerfEventKind.REQUEST_FINISHED }
            ?: events.lastOrNull { it.kind == PerfEventKind.CHECKPOINT }
            ?: events.firstOrNull { it.kind == PerfEventKind.REQUEST_ARRIVED } ?: events.first()
}

// Charge 512 bytes per request's scalar reduction state before its retained identifier text.
private const val REQUEST_FIXED_BYTES = 512L
private const val EVIDENCE = "evidence"
private data class ReducedInput(
    val identity: PerfIdentity,
    val scope: PerfScope,
    val links: PerfLinks,
    val outcome: String?,
)

// Keep the global 128 KiB representative timeline plus its 32 KiB bookkeeping inside the unchanged 3 MiB lease.
private const val TIMELINE_RESERVATION_BYTES = 160L * 1_024
private val REDUCED_KINDS = setOf(
    PerfEventKind.REQUEST_ARRIVED,
    PerfEventKind.CHECKPOINT,
    PerfEventKind.REQUEST_FINISHED,
    PerfEventKind.USAGE_RECORDED,
    PerfEventKind.ATTEMPT_STARTED,
    PerfEventKind.ATTEMPT_FINISHED,
)
