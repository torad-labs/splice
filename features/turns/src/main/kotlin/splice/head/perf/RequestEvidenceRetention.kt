package splice.head.perf

import kotlinx.serialization.Serializable

/** Expiry is dated by segment write time. It never proves event-time completeness for late usage. */
@Serializable
public data class RequestEvidenceRetention(
    val expiredSegments: Long = 0,
    val expiredBeforeWriteMs: Long? = null,
    val failedMaintenance: Long = 0,
    /** Rejected or pending work cannot be recovered after process loss from these files alone. */
    val lossAccountingComplete: Boolean = false,
)

/** Only these files belong to the new evidence store; legacy perf logs and full traces are separate. */
public object RequestEvidenceFormat {
    public const val RETENTION_FILE: String = "retention.json"
    private val segment = Regex(
        "evidence-([A-Za-z0-9][A-Za-z0-9_.-]{0,127})-[0-9]+-" +
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jsonl",
    )

    public fun isSegment(name: String): Boolean = segment.matches(name)

    /** The writer puts exactly one head into a segment. Never infer a head from an unrecognised filename. */
    public fun headOf(name: String): String? = segment.matchEntire(name)?.groupValues?.get(1)
}
