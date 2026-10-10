// NEW: V4-174/V4-387 — where each head's default-on trace store is built: null only when that
// head explicitly opts out with `trace = false`, day files named after the head under the
// owner-only trace directory, the head's own retention and body cap. Its own file because
// ManagedHeadFactory is already the app's densest assembly and the concentration ratchet said so.
package splice.app.head

import splice.core.config.SpliceConfig
import splice.core.config.StatePaths
import splice.core.storage.ActivityDays
import splice.core.storage.DayBodyBudget
import splice.core.storage.RetentionDays
import splice.head.wire.TraceStore

internal class HeadTraceStores(
    private val statePaths: StatePaths,
    private val bodyBudget: DayBodyBudget = DayBodyBudget(),
) {

    /** Every head gets its store, recording or not as its config says at boot: the capture switch turns recording on
     *  and off from there without a restart, so a head that opted out must still have a store to turn on. */
    fun forHead(key: String, cfg: SpliceConfig): TraceStore {
        val days = ActivityDays(
            statePaths.traceDir,
            key,
            cfg.traceKeptDays,
            ownerOnly = true,
            // Read again at every use, so a change to the history window moves the cut with no restart.
            live = RetentionDays { cfg.current().traceKeptDays },
        )
        return TraceStore(
            days,
            key,
            cfg.traceMaxBodyChars,
            bodyBudget = bodyBudget,
        ).also { it.recording.set(cfg.trace) }
    }
}
