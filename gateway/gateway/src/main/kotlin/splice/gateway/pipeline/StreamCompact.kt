// NEW: Compact-stats recording for TurnPipeline's finish path. Split from
// StreamFinish (concentration, 2026-08-19) so that file is not billed for the
// compact subsystem. Same-package.
package splice.gateway.pipeline

import splice.gateway.compact.CompactStats

internal class StreamCompact(private val compactStats: CompactStats) {
    fun recordStreamError(elapsedMs: Long, error: String) {
        record("stream_error", elapsedMs, error = error)
    }

    fun record(outcome: String, elapsedMs: Long, chars: Int? = null, error: String? = null) {
        compactStats.record(
            buildMap {
                put("outcome", outcome)
                put("ms", elapsedMs)
                chars?.let { put("chars", it) }
                error?.let { put("error", it) }
            },
        )
    }
}
