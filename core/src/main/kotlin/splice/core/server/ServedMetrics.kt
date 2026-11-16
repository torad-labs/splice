// NEW: server mode (docs/specs/server-mode.md, F6). The server's per-person metrics as one snapshot, in a shape both of
// their readers take: the listener writes it as Prometheus text for an admin's scrape, and the operator's OTLP export
// sends it to their collector. Counts, durations and rates per person and team; never a word of a prompt or an answer.
// These families carry who sent what by design, so they stay apart from the operator families, which are
// identity-free (docs/observability/exporters.md).
package splice.core.server

/** One series' labels, in the order they are written. */
public data class MetricLabels(val pairs: List<Pair<String, String>>) {
    /** The value of [name], or null where the series has no such label. */
    public fun of(name: String): String? = pairs.firstOrNull { it.first == name }?.second

    /** These labels with [name] set to [value] after them. */
    public fun plus(name: String, value: String): MetricLabels = MetricLabels(pairs + (name to value))
}

/** A counter's or a gauge's value for one series. */
public data class MetricPoint(val labels: MetricLabels, val value: Double)

/** A histogram's series: [buckets] holds how many observations fell at or under each bound and above the one before
 *  it, then those above the last bound, so it has one entry more than the family's bounds. */
public data class HistogramPoint(val labels: MetricLabels, val buckets: List<Long>, val sum: Double) {
    public val count: Long get() = buckets.sum()
}

/** One metric family: its name as both exports spell it, what it counts, and its unit. */
public sealed class MetricFamily {
    public abstract val name: String
    public abstract val help: String
    public abstract val unit: String

    /** A count that only grows while the daemon runs. */
    public data class Counter(
        override val name: String,
        override val help: String,
        override val unit: String,
        val points: List<MetricPoint>,
    ) : MetricFamily()

    /** A value as it is now. */
    public data class Gauge(
        override val name: String,
        override val help: String,
        override val unit: String,
        val points: List<MetricPoint>,
    ) : MetricFamily()

    /** Observations counted into [bounds], each an upper bound, ascending. */
    public data class Histogram(
        override val name: String,
        override val help: String,
        override val unit: String,
        val bounds: List<Double>,
        val points: List<HistogramPoint>,
    ) : MetricFamily()
}

/** Every family as it reads at [atMs], counted since [sinceMs] (epoch milliseconds): counters and histograms start
 *  at zero when the daemon does. */
public data class MetricSnapshot(val sinceMs: Long, val atMs: Long, val families: List<MetricFamily>)

/** Where a snapshot of the server's metrics is read. */
public fun interface ServedMetricSource {
    public fun snapshot(): MetricSnapshot
}
