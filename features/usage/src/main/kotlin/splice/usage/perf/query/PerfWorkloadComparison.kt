package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObservation
import splice.usage.perf.query.PerfEvidenceWindow.inside
import splice.usage.perf.query.PerfQueryJson.strings
import splice.usage.perf.query.PerfQueryJson.textValue
import java.security.MessageDigest

internal object PerfWorkloadComparison {
    fun unavailable(): JsonObject = buildJsonObject {
        put("status", "unavailable")
        put("population_complete", false)
        put("distribution_id", textValue(null))
        put("reasons", strings(listOf("canonical_request_evidence_unavailable")))
    }

    fun fold(
        all: Collection<List<PerfObservation>>,
        selected: List<List<PerfObservation>>,
        query: PerfQuery,
        complete: Boolean,
    ): JsonObject {
        val cohort = selected.filter { arrived(it, query) }
        val measured = cohort.map(PerfWorkloadFacts::measure)
        val reasons = measured.flatMap { it.reasons }.toMutableSet()
        if (cohort.isEmpty()) reasons.add("no_observed_arrivals")
        if (!complete) reasons.add("request_population_partial")
        val membershipUnknown = all.filter { arrived(it, query) }.any { unresolved(it, query) }
        if (membershipUnknown) reasons.add("cohort_membership_unresolved")
        val partitions = measured.mapNotNull { it.partition }.groupingBy { it }.eachCount()
        if (partitions.size > MAX_PARTITIONS) reasons.add("workload_partition_limit")
        val known = complete && reasons.isEmpty()
        return buildJsonObject {
            put("status", status(cohort.size, known))
            put("population_complete", complete && !membershipUnknown)
            put("observed_arrivals", cohort.size)
            put("measured_requests", measured.count { it.partition != null })
            put("matching_requests", measured.count { it.reasons.isEmpty() })
            put("matching_basis", MATCHING_BASIS)
            put("distribution_id", textValue(if (known) identity(partitions) else null))
            put("reasons", strings(reasons.sorted()))
            put("truncated", partitions.size > MAX_PARTITIONS)
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

    private fun status(count: Int, complete: Boolean): String = when {
        count == 0 -> "empty"
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

private const val MAX_PARTITIONS = 256
internal const val MATCHING_BASIS = "exact_joint_frequency_v1"
