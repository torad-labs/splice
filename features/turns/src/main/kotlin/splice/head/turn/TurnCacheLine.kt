// NEW: the cache log line + UsageHud, split from TurnTelemetry (concentration, 2026-08-19)
// so the observability file is not billed for the hud's subsystem. Same-package.
package splice.head.turn

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.PerfKeys
import splice.head.usage.UsageHud

internal class TurnCacheLine(
    private val headKey: String,
) {
    private val hud = UsageHud()

    /**
     * The cache line of a turn, read from the [counters] its perf row carries, so the log and the row never
     * disagree. [model] is the drive's upstream model; [headKey] is the same tag every other TurnTelemetry line
     * uses. Null for a row that billed no input: a step answered without asking the provider, or a turn whose
     * provider reported no usage. Such a row has no hit rate, and a `hit=0%` line would read as a cache miss.
     *
     * Nov 16, 2026: the line was written when the client's answer ended, from the usage known at that moment. A
     * code-mode turn that ends on a tool call ends before its provider reports usage, so the log said
     * `input=0 cached=0 hit=0%` for turns whose rows then recorded 230,000 input tokens at 95% cached, and said
     * the same for every local step. Read from the log alone, most of a healthy head's turns looked uncached.
     */
    fun rowLine(model: String, counters: Map<String, Long>, compact: Boolean): String? {
        val input = counters[PerfKeys.IN_TOKENS]?.takeIf { it > 0L } ?: return null
        val cached = counters[PerfKeys.CACHED_TOKENS] ?: 0L
        val usage = buildJsonObject {
            put("input_tokens", input)
            put("output_tokens", counters[PerfKeys.OUT_TOKENS] ?: 0L)
            put("input_tokens_details", buildJsonObject { put("cached_tokens", cached) })
        }
        return hud.cacheLogLine(headKey, model, usage, compact)
    }
}
