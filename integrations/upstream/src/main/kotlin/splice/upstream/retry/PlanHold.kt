// NEW: V4-233's plan hold. When Anthropic's unified family names a PLAN window spent (status
// rejected, a five_hour or seven_day claim, and the plain reset), a re-send before that reset cannot
// succeed, so the head hands its clients the reset instead of the cooldown's 120s lift, and a
// persistent client sleeps once, until the plan comes back.
//
// It sits beside RateLimitCooldown, which owns one and clears it on restart, rather than inside it,
// because that class is at detekt's function ceiling. It holds no turn: each turn asks the provider
// itself (Marcos, Oct 26), so the next one notices a top-up. What this adds is only the deadline the
// CLIENT is told and the window status reads.
//
// Nov 3, 2026: a window of one model's week holds the login for that model alone (PlanLimit.oneModel), beside the one
// window of the whole login, each until its own reset; an answer for a model ends the whole login's hold and that
// model's, never another model's.
package splice.upstream.retry

import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.RetryNotice
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** [clock] is the owning cooldown's ElapsedClock, so the hold and the horizon share one base;
 *  [wallClock] converts the upstream's absolute reset into a delay on it, as captureProviderReset
 *  does, because the two bases must never mix. */
public class PlanHold internal constructor(
    private val clock: ElapsedClock,
    private val wallClock: WallClock,
    /** V4-412: where the hold is kept between restarts; the default remembers nothing on disk. */
    private val holds: ProviderHolds = ProviderHolds(null),
) {
    /** The held windows by the model each holds the login for, the whole login's under null. */
    private val held = AtomicReference<Map<String?, Held>>(emptyMap())

    /** Records [limit] and returns the delay until its reset, or null when that reset has already
     *  passed, or when it is one model's week and names no model, which holds nothing: splice cannot tell which
     *  turns it refuses. For each model, and for the whole login, the later reset wins, as on every horizon in
     *  RateLimitCooldown. */
    public fun hold(limit: PlanLimit, onRetry: RetryNotice): Long? {
        val delayMs = limit.resetEpochSeconds * MS_PER_S - wallClock()
        if (delayMs <= 0L || limit.modelUnknown) return null
        val candidate = Held(clock() + delayMs, limit)
        holds.refusing {
            val kept = held.updateAndGet { current ->
                val prior = current[limit.model]
                val later = prior == null || candidate.untilMs > prior.untilMs
                if (later) current + (limit.model to candidate) else current
            }
            write(kept)
        }
        val held = limit.model?.let { " for $it" }.orEmpty()
        onRetry(
            "429 plan limit: the upstream names its ${limit.claim} window spent$held until " +
                "${Instant.ofEpochSecond(limit.resetEpochSeconds)}; clients are told to come back then",
        )
        return delayMs
    }

    /** How long the whole login's held window stays spent; 0 when no such hold is live. */
    public fun forMs(): Long = held.get()[null]?.let { maxOf(0L, it.untilMs - clock()) } ?: 0L

    /** The whole login's held window, exactly as the upstream named it, while the hold is live; null otherwise. */
    public fun live(): PlanLimit? = held.get()[null]?.takeIf { it.untilMs > clock() }?.limit

    /** The held window that refuses a turn of [model]: the whole login's, else that model's week; null when neither
     *  is live. A null [model] meets the whole login's alone. */
    public fun live(model: String?): PlanLimit? =
        live() ?: model?.let { held.get()[it] }?.takeIf { it.untilMs > clock() }?.limit

    /** Each live week of one model, as the upstream named it, the soonest reset first. */
    public fun models(): List<PlanLimit> = held.get().entries
        .filter { (model, hold) -> model != null && hold.untilMs > clock() }
        .map { it.value.limit }
        .sortedBy(PlanLimit::resetEpochSeconds)

    /** An answered turn of [model] ends the whole login's hold and that model's: the window is open again (a top-up,
     *  or a reset that came early). Another model's week stays held: an Opus answer says nothing of Fable's week. A
     *  restart does not (V4-412): the upstream's statement outlives the process, and the first turn after it asks the
     *  provider anyway. */
    internal fun clear(model: String? = null) {
        write(held.updateAndGet { current -> current - setOf(null, model) })
    }

    /** Ends the week of [model] alone: the provider's own reading shows it reset before the time its refusal named
     *  (EarlyReset). The whole login's hold and every other model's week stay. */
    internal fun release(model: String) {
        write(held.updateAndGet { current -> current - model })
    }

    /** V4-412: brings back a hold a restart interrupted, silently and without writing it again;
     *  a reset that has already passed restores nothing, and neither does one model's week that names no model. */
    internal fun restore(limit: PlanLimit) {
        val delayMs = limit.resetEpochSeconds * MS_PER_S - wallClock()
        if (delayMs > 0L && !limit.modelUnknown) {
            held.updateAndGet { it + (limit.model to Held(clock() + delayMs, limit)) }
        }
    }

    private fun write(kept: Map<String?, Held>) {
        holds.plans(kept[null]?.limit, kept.filterKeys { it != null }.values.map(Held::limit))
    }
}

private data class Held(val untilMs: Long, val limit: PlanLimit)
