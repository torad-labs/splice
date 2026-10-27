// NEW: failure-handling slice 4. What a turn does next about a failure, as RetryEngine decides it
// (final-design.md section 8). The HTTP attempt loop and the head's round controller both execute these steps, and
// every wait among them goes through the turn's TurnWaits.
package splice.upstream.retry

import splice.core.failure.FailureCause
import java.time.Instant

/** One step of a turn's recovery. */
public sealed class RetryStep {
    /** Send again after [afterMs], in the way [how] names. */
    public data class Again(val afterMs: Long, val how: How) : RetryStep()

    /** Move the turn to another of the head's logins, before any content reached the client. */
    public data object SwitchLogin : RetryStep()

    /** Hold the turn until [until], or until a probe finds the cause lifted where no time was stated. Until slice 5
     *  brings holds, a hold step ends the turn at once with today's R wire and the interim line for its kind. */
    public data class Hold(val until: Instant?, val probes: Probes) : RetryStep()

    /** End the turn as [cause]. */
    public data class End(val cause: FailureCause) : RetryStep()

    /** The answer as received stands: only a cut round reaches it, once its continuations are spent. */
    public data object Keep : RetryStep()
}

/** How an [RetryStep.Again] sends the request again. */
public enum class How {
    /** The same request. */
    SAME,

    /** After one token exchange renews the login it was refused on. */
    REFRESH_LOGIN,

    /** Continued from what arrived, where the head's re-anchor rule can pick up a cut answer. */
    REANCHOR,

    /** A reasoning-only answer, asked to go on from its reasoning (Muse). */
    CONTINUE_REASONING,

    /** With the stale reasoning the provider refused left out of the body. */
    AMEND_REASONING,

    /** A round whose reasoning was cut, continued with its reasoning replayed (codex's fold). */
    FOLD_CONTINUE,
}

/** What a hold probes while it waits: a firm reset, a soft one, nothing stated, or credit. NONE only for a hold with
 *  nothing to probe. */
public enum class Probes { NONE, FIRM, SOFT_LIFT, UNSTATED, CREDIT }
