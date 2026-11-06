package splice.observability.export

import splice.core.util.PerfWorkBudget

/** Keep this owner alive through UTF-8 encoding and delivery, then close it; do not retain its text afterward. */
public class PerfScrape internal constructor(
    public val text: String,
    public val state: String,
    public val sampledEpochMs: Long?,
    public val sourceUntilMs: Long?,
) : AutoCloseable {
    public val available: Boolean get() = state == "ready"
    override fun close(): Unit = PerfWorkBudget.release(this)
}

/** Output is ASCII, so the enforced character bound is also its encoded UTF-8 byte bound. */
public object PerfScrapeLimits {
    public const val MAX_TEXT_BYTES: Int = 256 * 1_024
    public const val RESPONSE_BYTES: Long = 3L * MAX_TEXT_BYTES + 4_096
    public const val MAX_AGE_MS: Long = 15_000
}

internal object ScrapeUnavailable {
    private val responses = listOf("disabled", "unsampled", "busy", "oversize", "stale", "closed", "source_failed")
        .associateWith { PerfScrape("# splice Prometheus scrape unavailable: $it\n", it, null, null) }

    fun of(state: String): PerfScrape = responses.getValue(state)
}
