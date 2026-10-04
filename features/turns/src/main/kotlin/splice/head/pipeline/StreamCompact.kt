// NEW: Compact-stats recording for TurnPipeline's finish path. Split from
// StreamFinish (concentration, 2026-08-19) so that file is not billed for the
// compact subsystem. Same-package.
package splice.head.pipeline

import splice.core.turn.TurnMeta
import splice.head.compact.CompactStats

internal class StreamCompact(private val compactStats: CompactStats) {
    fun recordStreamError(meta: TurnMeta, elapsedMs: Long, error: String) {
        record(meta, "stream_error", elapsedMs, error = error)
    }

    fun record(meta: TurnMeta, outcome: String, elapsedMs: Long, chars: Int? = null, error: String? = null) {
        compactStats.record(
            buildMap {
                put("outcome", outcome)
                put("ms", elapsedMs)
                chars?.let { put("chars", it) }
                error?.let { put("error", it) }
                meta.compactionInstructions?.let { put("instructions", it) }
                meta.compactionInstructionsSource?.let { put("instructions_source", it) }
            },
        )
    }
}
