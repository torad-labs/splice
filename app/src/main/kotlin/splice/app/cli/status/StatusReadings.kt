// NEW: Nov 21, 2026 — split from StatusTable.kt along its seam: what the daemon reports of each head beyond its
// topology row is read here, and the table only draws it.
package splice.app.cli.status

import splice.core.head.HeldTurnReading
import splice.core.usage.QuotaFull

/** What the daemon reports about each head beyond its topology row, keyed by head: the boot failure, the instant
 *  its quota refusal lifts, the endpoint of a silent runtime, and a spent plan window. */
internal class StatusReadings(
    private val failedHeads: Map<String, String> = emptyMap(),
    private val quotaResetAtEpochSeconds: Map<String, Long> = emptyMap(),
    private val runtimeNotAnswering: Map<String, String> = emptyMap(),
    private val quotaFull: Map<String, QuotaFull> = emptyMap(),
    private val held: Map<String, List<HeldTurnReading>> = emptyMap(),
    /** 2026-11-09 audit: each head refusing every request, and why it refused the last. */
    private val refusing: Map<String, String> = emptyMap(),
) {
    /** The heads the daemon names failed that [known] does not hold, in key order. */
    fun failedOutside(known: Set<String>): Map<String, String> = failedHeads.filterKeys { it !in known }.toSortedMap()

    fun wordFor(key: String): HeadWord =
        HeadWord(
            failedHeads[key],
            quotaResetAtEpochSeconds[key],
            runtimeNotAnswering[key],
            quotaFull[key],
            held[key].orEmpty(),
            refusing[key],
        )
}

/** What status learned about one head beyond its config: why the daemon could not boot it (V4-394), the instant its
 *  provider stops refusing turns, epoch seconds (V4-398), the endpoint of its local runtime when that does not answer
 *  (V4-415), and the provider's reading when it names a window fully used (V4-452). All null for a head that is
 *  fine. */
internal data class HeadWord(
    val bootFailure: String?,
    val quotaResetAtEpochSeconds: Long?,
    val runtimeEndpoint: String?,
    val quotaFull: QuotaFull? = null,
    /** Failure-handling slice 5: the turns the head holds until their cause lifts, oldest first. */
    val held: List<HeldTurnReading> = emptyList(),
    /** 2026-11-09 audit: why a head refusing every request refused the last one, null while it admits turns. */
    val refusing: String? = null,
)
