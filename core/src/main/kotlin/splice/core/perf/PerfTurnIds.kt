// NEW: shared local perf trace lookup and request ownership, separate from live-turn stop identity.
package splice.core.perf

/** Local record identities. Only [trace] names captured bodies; [request] joins a perf row to sent records.
 *  Neither value is the live-turn stop identity. */
public data class PerfTurnIds(
    public val trace: String? = null,
    public val request: String? = trace,
)
