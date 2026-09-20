// NEW: V4-174 — where a head's opt-in trace store is built: ONLY for a head whose own cfg says
// `trace = true` (keyed, never the global view), day files named after the head under the
// owner-only trace directory, the head's own retention and body cap. Its own file because
// ManagedHeadFactory is already the app's densest assembly and the concentration ratchet said so.
package splice.app.head

import splice.core.activity.ActivityDays
import splice.core.config.SpliceConfig
import splice.core.config.StatePaths
import splice.gateway.wire.TraceStore

internal class HeadTraceStores(private val statePaths: StatePaths) {

    /** Null — off — for every head that did not opt in; nothing is written and no directory exists. */
    fun forHead(key: String, cfg: SpliceConfig): TraceStore? {
        if (!cfg.trace) return null
        val days = ActivityDays(statePaths.traceDir, key, cfg.traceRetentionDays, ownerOnly = true)
        return TraceStore(days, key, cfg.traceMaxBodyChars)
    }
}
