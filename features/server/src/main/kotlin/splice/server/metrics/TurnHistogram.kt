// NEW: server mode (docs/specs/server-mode.md, F6). The histograms the server keeps per person and team, each with its
// name as both exports spell it, its unit and its buckets.
package splice.server.metrics

import splice.core.server.HistogramPoint
import splice.core.server.MetricLabels

internal enum class TurnHistogram(val metric: String, val help: String, val unit: String, val bounds: List<Double>) {
    TTFT(
        "splice_server_ttft_seconds",
        "Seconds from a request's arrival to the first word of its answer.",
        SECONDS,
        listOf(0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0),
    ),
    DURATION(
        "splice_server_duration_seconds",
        "Seconds from a request's arrival to the end of its answer, for every request the server did not refuse.",
        SECONDS,
        listOf(0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0),
    ),
    PREFILL(
        "splice_server_prefill_tokens_per_second",
        "Prompt tokens a second the model server read, as it reported its own timings.",
        TOKENS_PER_SECOND,
        listOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0),
    ),
    DECODE(
        "splice_server_decode_tokens_per_second",
        "Answer tokens a second the model server wrote, as it reported its own timings.",
        TOKENS_PER_SECOND,
        listOf(5.0, 10.0, 20.0, 40.0, 60.0, 80.0, 120.0, 200.0, 400.0),
    ),
    STALL(
        "splice_server_stream_stall_seconds",
        "The longest a streamed answer waited for the upstream's next words after its first, in seconds.",
        SECONDS,
        listOf(0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0),
    ),
}

/** One series of a histogram: how many observations fell in each of [bounds], then above the last, and their sum. */
internal class Buckets(private val bounds: List<Double>) {
    private val counts = LongArray(bounds.size + 1)
    private var sum = 0.0

    @Synchronized
    fun observe(value: Double) {
        val at = bounds.indexOfFirst { value <= it }
        counts[if (at < 0) bounds.size else at]++
        sum += value
    }

    @Synchronized
    fun point(labels: MetricLabels): HistogramPoint = HistogramPoint(labels, counts.toList(), sum)
}

private const val SECONDS = "s"
private const val TOKENS_PER_SECOND = "{token}/s"
