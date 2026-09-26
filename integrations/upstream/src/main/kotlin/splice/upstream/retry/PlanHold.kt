// NEW: V4-233's plan hold. When Anthropic's unified family names a PLAN window spent (status
// rejected, a five_hour or seven_day claim, and the plain reset), a re-send before that reset cannot
// succeed, so the head hands its clients the reset instead of the cooldown's 120s lift, and a
// persistent client sleeps once, until the plan comes back.
//
// It sits beside RateLimitCooldown, which owns one and clears it on restart, rather than inside it,
// because that class is at detekt's function ceiling. The fail-fast horizon is NOT extended to the
// reset: V4-47's load-bearing decision in RateLimitCooldown's header stands, so the horizon still
// lifts at the clamp and the next turn is a probe that notices a top-up. What this adds is only the
// deadline the CLIENT is told.
package splice.upstream.retry

import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.ErrorEnvelope
import splice.upstream.RetryNotice
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** [clock] is the owning cooldown's ElapsedClock, so the hold and the horizon share one base;
 *  [wallClock] converts the upstream's absolute reset into a delay on it, as captureProviderReset
 *  does, because the two bases must never mix. */
public class PlanHold internal constructor(
    private val clock: ElapsedClock,
    private val wallClock: WallClock,
) {
    private val held = AtomicReference<Held?>(null)

    /** Records [limit] and returns the delay until its reset, or null when that reset has already
     *  passed. The later reset wins, as on every horizon in RateLimitCooldown. */
    internal fun hold(limit: PlanLimit, onRetry: RetryNotice): Long? {
        val delayMs = limit.resetEpochSeconds * MS_PER_S - wallClock()
        if (delayMs <= 0L) return null
        val candidate = Held(clock() + delayMs, limit)
        held.updateAndGet { current ->
            if (current == null || candidate.untilMs > current.untilMs) candidate else current
        }
        onRetry(
            "429 plan limit: the upstream names its ${limit.claim} window spent until " +
                "${Instant.ofEpochSecond(limit.resetEpochSeconds)}; clients are told to come back then, " +
                "and a turn after the cooldown lifts probes upstream",
        )
        return delayMs
    }

    /** How long the held window stays spent; 0 when no hold is live. */
    public fun forMs(): Long = held.get()?.let { maxOf(0L, it.untilMs - clock()) } ?: 0L

    /** The held window, exactly as the upstream named it, while the hold is live; null otherwise. */
    public fun live(): PlanLimit? = held.get()?.takeIf { it.untilMs > clock() }?.limit

    /** An answered turn or a restart ends the hold: either the window is open again (a top-up, or
     *  a reset that came early) or the operator chose to ask the upstream afresh. */
    internal fun clear() {
        held.set(null)
    }

    /** V4-234: what a client is told when the upstream named a spent plan window. Our sentence, in
     *  the same envelope a real upstream sends, because whether a persistent Claude Code keeps
     *  waiting is decided by the words it reads here, and the reset is the signal that should decide
     *  it. No em dash, and none of the client's stop phrases (RateLimitRefusalClientContractTest
     *  holds the list). */
    internal fun clientBody(limit: PlanLimit): String =
        ErrorEnvelope.of(
            "rate_limit_error",
            "Rate limit exceeded: the upstream reports this plan's ${limit.windowWords} window is used up " +
                "until ${Instant.ofEpochSecond(limit.resetEpochSeconds)}. The session waits and resumes after " +
                "the reset.",
        ).toString()
}

private data class Held(val untilMs: Long, val limit: PlanLimit)
