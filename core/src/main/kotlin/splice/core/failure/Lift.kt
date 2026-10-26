// NEW: failure-handling slice 1 (2026-10-26). When a limit lifts, as the provider stated it or not at all.
package splice.core.failure

import java.time.Instant

/** When a limit or an overload lifts. */
public sealed class Lift {
    /** [firm] is false only where a provider deviation shows its stated times are stamped on failures that clear
     *  sooner. [source] says which part of the answer stated it. */
    public data class At(val at: Instant, val firm: Boolean, val source: LiftSource) : Lift()

    /** The answer named no time. */
    public data object Unstated : Lift()
}

public enum class LiftSource { RETRY_AFTER, RESET_HEADER, BODY }
