// NEW: one turn's first upstream routing header, shared by its retries and continuation rounds.
package splice.core.turn

/** A first-write-wins header holder. Providers choose the header; transports only echo its snapshot. */
public class TurnUpstreamHeaders {
    private var captured: Pair<String, String>? = null
    private var httpOmissionNoticed = false

    /** An absent header does not initialize the holder, and later observations cannot replace it. */
    @Synchronized
    public fun capture(name: String, value: String?) {
        if (captured == null && value != null) captured = name to value
    }

    /** An immutable request snapshot, empty until the upstream supplies a value. */
    @Synchronized
    public fun snapshot(): Map<String, String> = captured?.let { mapOf(it) }.orEmpty()

    /** Claims one value-free HTTP omission notice for this turn, not one per retry or round. */
    @Synchronized
    public fun claimHttpOmissionNotice(): Boolean {
        if (httpOmissionNoticed) return false
        httpOmissionNoticed = true
        return true
    }
}
