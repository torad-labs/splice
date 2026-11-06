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
internal object PerfScrapeLimits {
    // Cap the fixed ASCII renderer at 256 KiB so metric-label growth cannot expand scrape responses indefinitely.
    public const val MAX_SCRAPE_TEXT_BYTES: Int = 256 * 1_024

    // Reserve the text's UTF-16 representation, ASCII response bytes and 4 KiB of response ownership overhead.
    public const val RESPONSE_BYTES: Long = 3L * MAX_SCRAPE_TEXT_BYTES + 4_096

    // After three five-second sampling intervals, report a stale scrape instead of presenting old counters as current.
    public const val MAX_SCRAPE_AGE_MS: Long = 15_000
}

internal object ScrapeUnavailable {
    private val responses = listOf("disabled", "unsampled", "busy", "oversize", "stale", "closed", "source_failed")
        .associateWith { PerfScrape("# splice Prometheus scrape unavailable: $it\n", it, null, null) }

    fun of(state: String): PerfScrape = responses.getValue(state)
}
