// NEW: Oct 11, 2026 — what a window read keeps of a line while it is first read, before the dense store takes it.
package splice.app.sources

import splice.core.perf.PerfTurnIds
import splice.usage.perf.PerfTranscriptLink

/** Drops what a dense record does not keep (the trace, request and response ids, the provider's words) the moment
 *  the line is read, so a cold fill of a large window never holds them for every row, and marks the line as one the
 *  cache places, so the row it stands for can be read back whole. */
internal class PerfCompactRetention : PerfLineKeep {
    override fun keep(line: PerfCachedLine, names: PerfFieldNames): PerfCachedLine {
        val row = line.row ?: return line.copy(retention = PerfRetention(textBytes = 0L, located = true))
        return line.copy(
            row = row.copy(
                facts = row.facts.copy(providerMessage = null),
                turns = PerfTurnIds(),
                transcript = PerfTranscriptLink(sessionId = row.transcript.sessionId),
            ),
            retention = PerfRetention(textBytes = 0L, located = true),
        )
    }
}
