package splice.usage.perf.query.reduction

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObservation
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryJson.strings
import splice.usage.perf.query.PerfQueryJson.textValue
import splice.usage.perf.query.reduction.PerfEvidenceWindow.inside
import java.security.MessageDigest

internal class PerfWorkloadComparison(private val query: PerfQuery) {
    private val partitions = LinkedHashMap<JsonObject, Int>()
    private val reasons = linkedSetOf<String>()
    private var arrivals = 0L
    private var measured = 0L
    private var matching = 0L
    private var membershipUnknown = false
    private var truncated = false

    fun add(events: List<PerfObservation>, selected: Boolean) {
        if (!arrived(events, query)) return
        if (unresolved(events, query)) membershipUnknown = true
        if (!selected) return
        arrivals++
        val facts = PerfWorkloadFacts.measure(events)
        reasons.addAll(facts.reasons)
        if (facts.reasons.isEmpty()) matching++
        facts.partition?.let { partition ->
            measured++
            if (partition in partitions || partitions.size < MAX_PARTITIONS) {
                partitions[partition] = (partitions[partition] ?: 0) + 1
            } else {
                truncated = true
            }
        }
    }

    fun render(complete: Boolean): JsonObject {
        if (arrivals == 0L) reasons.add("no_observed_arrivals")
        if (!complete) reasons.add("request_population_partial")
        if (membershipUnknown) reasons.add("cohort_membership_unresolved")
        if (truncated) reasons.add("workload_partition_limit")
        val known = complete && reasons.isEmpty()
        return buildJsonObject {
            put("status", status(arrivals, known))
            put("population_complete", complete && !membershipUnknown)
            put("observed_arrivals", arrivals)
            put("measured_requests", measured)
            put("matching_requests", matching)
            put("matching_basis", MATCHING_BASIS)
            put("distribution_id", textValue(if (known) identity(partitions) else null))
            put("reasons", strings(reasons.sorted()))
            put("truncated", truncated)
            put(
                "partitions",
                JsonArray(
                    partitions.entries.sortedBy { it.key.toString() }.take(MAX_PARTITIONS).map {
                        JsonObject(it.key + buildJsonObject { put("count", it.value) })
                    },
                ),
            )
        }
    }

    private fun status(count: Long, complete: Boolean): String = when {
        count == 0L -> "empty"
        complete -> "complete"
        else -> "partial"
    }

    private fun arrived(events: List<PerfObservation>, query: PerfQuery): Boolean =
        events.any { it.kind == PerfEventKind.REQUEST_ARRIVED && inside(it, query) }

    private fun unresolved(events: List<PerfObservation>, query: PerfQuery): Boolean {
        val event = PerfWorkloadFacts.representative(events)
        val links = event.links
        val missingLinks = query.filters.keys.any {
            when (it) {
                "session" -> links.sessionId == null
                "project" -> links.sessionId == null && links.projectId == null
                "account" -> links.accountId == null
                "outcome" -> event.outcome == null && event.scope.outcome == null
                else -> false
            }
        }
        return !PerfWorkloadFacts.resolved(event.scope) || missingLinks
    }

    private fun identity(partitions: Map<JsonObject, Int>): String {
        val divisor = partitions.values.reduce(::gcd)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(MATCHING_BASIS.toByteArray(Charsets.UTF_8))
        partitions.entries.sortedBy { it.key.toString() }.forEach {
            digest.update("\n${it.key}:${it.value / divisor}".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun gcd(left: Int, right: Int): Int {
        var a = left
        var b = right
        while (b != 0) { val next = a % b; a = b; b = next }
        return a
    }
}

// Keep at most 256 exact workload partitions and expose omissions rather than claiming unmatched workloads are comparable.
private const val MAX_PARTITIONS = 256
internal const val MATCHING_BASIS = "exact_joint_frequency_v1"
