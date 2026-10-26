// NEW: failure-handling slice 1 (2026-10-26). Why splice refused a request before any provider saw it: the facts of
// FailureCause.Splice.Declined, apart from the leaves so each file holds one idea.
package splice.core.failure

import java.time.Instant

/** Why splice refused a request before any provider saw it. Each case carries only the facts its site has. */
public sealed class Decline {
    /** The body is over the head's size limit, or over what the heap can hold at once. */
    public data class TooLarge(val bytes: Long?, val limit: Long) : Decline()

    /** The heap is busy with other bodies; this one fits once they are done. */
    public data object HeapBusy : Decline()

    /** The head's daily spend budget is reached and its action is block, until the budget's day ends at [resetsAt],
     *  the machine's own midnight. [limit] is the operator's own figure as splice spells an amount: V4-240 keeps every
     *  dollar figure in one place (splice.usage.ApiCostText.limit), which core cannot reach, so the amount arrives
     *  spelled and the line around it is still SpliceLines'. */
    public data class Budget(val limit: String, val resetsAt: Instant) : Decline()

    /** The compaction preflight's size bound refused the request before sending it, against the model's window of
     *  [windowTokens]. */
    public data class Preflight(val kind: PreflightKind, val windowTokens: Long) : Decline()

    /** The body could not be read whole or parsed. */
    public data class BadBody(val readBytes: Long, val declaredBytes: Long?) : Decline()

    public data object ReadTimeout : Decline()

    /** A model this head does not serve. */
    public data class UnknownModel(val model: String) : Decline()

    /** A request whose Host header is not this machine (the DNS-rebinding refusal). */
    public data object ForeignHost : Decline()

    /** The local gateway credentials were missing or wrong. */
    public data object BadLocalKey : Decline()

    /** A liveness probe, which never dispatches a turn. */
    public data object LivenessProbe : Decline()

    /** A turn shared from another machine runs on a login this machine holds, and this head has none free for it. */
    public data object NoSharedLogin : Decline()

    /** A request feature the head cannot carry or guarantee. */
    public data class Untranslatable(val what: RequestFeature) : Decline()
}

public enum class PreflightKind { COMPACT_OVERFLOW, COMPACTABLE, FIRST_EXCHANGE }

public enum class RequestFeature { OUTPUT_FORMAT, STRUCTURED_OUTPUT, TOOL_CHOICE }
