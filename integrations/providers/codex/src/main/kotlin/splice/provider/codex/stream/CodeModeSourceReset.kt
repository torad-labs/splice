// NEW: names a torn source's upstream tries on the row of the client step its reset ended.
package splice.provider.codex.stream

import splice.core.failure.PersistedCause
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome

/**
 * A client step that its source stream's reset ended made no upstream try, so its row read attempts 0 beside
 * UPSTREAM_CONN_RESET. Its row names the tries the turn that posted the source made instead, as
 * [PerfKeys.SOURCE_ATTEMPTS]; attempts stays 0, so a retry total counts each try once. A step on the turn that
 * posted the source already carries those tries and gets no key.
 */
internal object CodeModeSourceReset {
    fun note(source: CodeModeLiveRound?, outcome: TurnOutcome, step: TurnPerf?) {
        val posted = source?.sourcePerf ?: return
        if ((outcome as? TurnOutcome.Failure)?.cause?.persisted != PersistedCause.UPSTREAM_CONN_RESET) return
        if (step != null && step !== posted) step.setCount(PerfKeys.SOURCE_ATTEMPTS, posted.count(PerfKeys.ATTEMPTS))
    }
}
