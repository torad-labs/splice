package splice.usage.perf.history

import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfObservation
import splice.core.util.PerfWorkBudget

public fun interface PerfObservationConsumer {
    public fun accept(observation: PerfObservation): Boolean
}

/** Collection proof for the consumed frozen population. Close after reduction; no observations are retained here. */
public data class PerfEvidenceReceipt(
    val coverage: PerfCoverage,
    val asOfMs: Long,
    val complete: Boolean,
    val coverageBasis: String = "unknown",
    val eventTimeFinal: Boolean = false,
) : AutoCloseable {
    override fun close(): Unit = PerfWorkBudget.release(this)
}

/** Compatibility bridge for bounded read implementations; file sources override with streaming consumption. */
internal object PerfEvidenceConsumption {
    fun consume(
        source: PerfEvidenceSource,
        query: PerfEvidenceQuery,
        consumer: PerfObservationConsumer,
    ): PerfEvidenceReceipt {
        val owner: PerfEvidenceRead = source.read(query)
        return owner.use { read ->
            val issues = ArrayList(read.coverage.issues)
            val changed = deliver(read.observations, consumer, issues)
            val coverage = read.coverage.copy(
                observedMs = if (changed) 0 else read.coverage.observedMs,
                issues = issues.distinct(),
            )
            val receipt =
                PerfEvidenceReceipt(
                    coverage,
                    read.asOfMs,
                    read.complete && !changed,
                    read.coverageBasis,
                    read.eventTimeFinal,
                )
            retain(receipt, query)
        }
    }

    private fun deliver(
        events: List<PerfObservation>,
        consumer: PerfObservationConsumer,
        issues: MutableList<PerfIssue>,
    ): Boolean {
        val seen = HashMap<String, PerfObservation>()
        var changed = false
        for (event in events) {
            val id = "${event.stamp.identity.bootId}:${event.stamp.observationId}"
            val prior = seen.putIfAbsent(id, event)
            if (prior != null && prior != event) {
                changed = true
                val issue = PerfIssue("evidence_duplicate_conflict", "request_evidence")
                if (issue !in issues) issues.add(issue)
            }
            if (prior == null && !consumer.accept(event)) {
                issues.add(PerfIssue("evidence_consumer_limit", "request_evidence"))
                return true
            }
        }
        return changed
    }

    private fun retain(receipt: PerfEvidenceReceipt, query: PerfEvidenceQuery): PerfEvidenceReceipt {
        val lease = PerfWorkBudget.acquire(RECEIPT_BYTES)
        return if (lease == null) {
            PerfEvidenceReceipt(
                PerfCoverage(
                    query.fromMs,
                    query.untilMs,
                    0,
                    listOf(PerfIssue("evidence_memory_busy", "request_evidence")),
                ),
                receipt.asOfMs,
                false,
            )
        } else {
            lease.retain(receipt, RECEIPT_BYTES)
            receipt
        }
    }
}

private const val RECEIPT_BYTES = 64L * 1_024
