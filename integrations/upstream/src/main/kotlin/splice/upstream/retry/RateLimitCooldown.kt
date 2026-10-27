// PORT-OF: splice/spi/UpstreamClient.kt (rateLimitedUntilMs and every one of its touch points) @ 3879c4c — invariants unchanged: NF-01's clamp and escape hatch, and latest-max arming.
//
// The shared 429 horizon (HD-25). Each pooled account owns one; legacy heads use the UpstreamClient's
// instance. A turn that gives up on a 429 marks its login limited here, and the mark only steers
// account selection and names the limit on status. It never holds or refuses another turn: each turn
// asks the provider itself and waits only on its own failure (Marcos, Oct 26). Until then this horizon
// made every later turn on the login sleep or fail fast with a 429 the provider never sent, and on
// claude-splice the ten slowest turns of Oct 25, 6 to 11 PM CT, waited 45 s to 2.3 min on it and then
// failed anyway.
//
// This is SHARED MUTABLE STATE, and that is exactly why every touch point moved together rather
// than the state being split from its rules: the AtomicLong, the arming, the clamp, the doctor read
// and the restart escape hatch are all here, and nothing outside this file names the horizon. The
// arming idiom used to be written TWICE — statusPlan's UP-001 arm and
// rateLimitedPlan's 429 arm each spelled out `clock() + minOf(pushback, MAX)` followed by
// `accumulateAndGet(max)` — and is now [arm], called from both.
//
// NF-01's wall retired to UpstreamClientRateLimitTest and HeadServerCapacityTest: the clamp, and
// clear() dropping an armed horizon through a real restart.
//
// V4-47 (2026-09-16): the provider's own reset is CAPTURED here and NAMED to the operator, but the
// horizon is deliberately NOT extended to it. THAT DECISION IS LOAD-BEARING — do not "simplify" the
// lift away. Two reasons, hardest first.
//  1. Restart is this file's ONLY escape hatch, so what steers selection must live on state clear()
//     can reach. Gate it on a horizon restart cannot clear and you rebuild the permanent poisoning
//     NF-01 exists to prevent, with no operator escape short of killing the daemon. V4-412: the
//     provider's reset and V4-233's plan hold are persisted and survive that call; the horizon is not.
//  2. The bounded lift is what DETECTS THE OPERATOR TOPPING UP. Extending the horizon to the
//     provider reset makes a pool steer away from a restored login for hours.
// Native credential-scoped replies restore a bounded horizon from their live reset instead. Startup
// keeps that horizon; it still expires at the same two-minute ceiling, never at a distant plan reset.
//
// BLOCKED destination, recorded so it is not re-proposed: features/turns/.../usage/RateLimitStore.kt
// is in :features-turns, and :upstream depends only on :core — that edge would invert.
package splice.upstream.retry

import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.NativeReply
import splice.upstream.RetryNotice
import splice.upstream.StreamStart
import java.util.concurrent.atomic.AtomicLong

/**
 * [clock] is the SAME instance UpstreamClient was constructed with, never a second one: the horizon
 * and the retry deadline must not split-brain across clock bases (review 2026-07-22). It is read
 * inside the arming method, after any preceding retry notice and token refresh, rather than
 * captured before refresh and passed in. Notice callbacks may advance a test clock too; the
 * cooldown starts when it is armed, not when the response headers were first received.
 */
public class RateLimitCooldown public constructor(
    private val clock: ElapsedClock,
    /** V4-47: WALL clock, used for exactly one thing — converting the provider's absolute reset
     *  instant (an ISO8601 time or epoch seconds in the 429 body) into the elapsed-clock DELAY this
     *  class stores. The two bases must never mix: every horizon above is elapsed, the provider
     *  speaks wall time, and comparing an epoch millisecond against an elapsed one reads as a reset
     *  ~50 years out. Defaulted, so every existing caller and test is unchanged. */
    private val wallClock: WallClock = WallClock(System::currentTimeMillis),
    /** V4-412: where the provider's word (its reset, the plan window it named spent) survives a
     *  restart; null keeps it in memory only, as before. The armed horizon is never stored. */
    store: ProviderHoldStore? = null,
) {
    // Armed by any attempt that observes a 429; while armed, every post() fails fast with a
    // synthesized 429 and ZERO upstream calls. Benign write race: concurrent arms only differ by
    // ms; latest-max wins.
    private val rateLimitedUntilMs = AtomicLong(0L)
    private val unavailableUntilMs = AtomicLong(0L)
    private val providerUnavailableUntilMs = AtomicLong(0L)

    private val holds = ProviderHolds(store)

    /** A native refusal belongs to this cooldown's credential, never to another caller on the head. */
    public var rateLimitReply: NativeReply?
        get() = holds.rateLimitReply
        set(value) { holds.rateLimitReply = value }

    /** V4-233: the plan window the upstream named as spent, held until the reset it named. Its own
     *  class, so this one keeps its function budget; a newer accepted request ends it ([acceptance]). */
    public val planHold: PlanHold = PlanHold(clock, wallClock, holds)

    init {
        val stored = holds.stored()
        val wallNow = wallClock()
        val resetAt = stored.providerResetAtEpochSeconds?.takeIf { it * MS_PER_S > wallNow }
        val plan = stored.plan?.takeIf { it.resetEpochSeconds * MS_PER_S > wallNow }
        resetAt?.let { at ->
            providerUnavailableUntilMs.set(clock() + minOf(at * MS_PER_S - wallNow, MAX_PROVIDER_RESET_MS))
        }
        plan?.let(planHold::restore)
        holds.restored(stored, ProviderHold(resetAt, plan))
        if (rateLimitReply != null) arm(providerUnavailableForMs())
    }

    /** The provider's reset as a wall instant in epoch seconds, or null when none is pending. */
    private val providerResetAt: Long?
        get() = maxOf(0L, providerUnavailableUntilMs.get() - clock()).takeIf { it > 0L }
            ?.let { (wallClock() + it) / MS_PER_S }

    /** Restart clears the translated horizon, retaining the provider's reporting facts.
     *  A retained native refusal keeps its credential's bounded horizon, including one restored from
     *  an unexpired reset. Startup cannot erase that refusal, and reads never extend the re-probe ceiling. */
    public fun clear() {
        if (rateLimitReply == null) rateLimitedUntilMs.set(0L)
    }

    /** Account-pool restart escape hatch; separate so NF-01's legacy [clear] wall stays exact. Clears
     *  the selection cooldown only: the provider's reset is a report, and outlives the restart (V4-412). */
    internal fun clearUnavailable() {
        unavailableUntilMs.set(0L)
    }

    /** Capture immediately before posting; invoke only on accepted headers. A newer refusal,
     *  including one learned at the same clock tick, survives this request's acceptance. */
    public fun acceptance(): StreamStart {
        val posted = holds.posted()
        return StreamStart {
            holds.accepted(posted) {
                rateLimitedUntilMs.set(0L)
                unavailableUntilMs.set(0L)
                providerUnavailableUntilMs.set(0L)
                holds.providerReset(null)
                planHold.clear()
                rateLimitReply = null
            }
        }
    }

    /** Marks the account unavailable to future turns, bounded by NF-01's recovery ceiling. */
    public fun markUnavailable(pushbackMs: Long): Unit = holds.refusing {
        val now = clock()
        val providerDelay = pushbackMs.coerceIn(0L, MAX_PROVIDER_RESET_MS)
        val boundedDelay = minOf(providerDelay, MAX_RATE_LIMIT_COOLDOWN_MS)
        // The elapsed-clock seam may be near Long.MAX_VALUE even though the delay is capped.
        val providerDelayLimit = Long.MAX_VALUE - now
        val boundedUntil = now + boundedDelay
        val providerUntil = now + minOf(providerDelay, providerDelayLimit)
        unavailableUntilMs.accumulateAndGet(boundedUntil) { current, candidate -> maxOf(current, candidate) }
        providerUnavailableUntilMs.accumulateAndGet(providerUntil) { current, candidate -> maxOf(current, candidate) }
        holds.providerReset(providerResetAt)
    }

    /** Remaining bounded account-selection cooldown (0 when the account may be selected). */
    public fun unavailableForMs(): Long = maxOf(0L, unavailableUntilMs.get() - clock())

    /** Provider reset for reporting, capped at seven days; never used to keep an account unavailable.
     *  PUBLIC since V4-50: the admission plane reads it to put a real deadline on the wire, which is
     *  the only place a client can act on it. Still never gates selection.
     *  V4-412: a held PLAN window is the provider naming its reset too (ChatGPT does it in the 429
     *  body, before any reset capture runs), so it counts: status and usage read the longer of the two. */
    public fun providerUnavailableForMs(): Long =
        maxOf(0L, providerUnavailableUntilMs.get() - clock(), planHold.forMs())

    /** V4-47: capture the provider's own reset from the 429 body, at ARM time and NOT gated on
     *  pooledAccount — markUnavailable is the only other writer and it fires only for pooled turns
     *  over the 15s ceiling, so on a single-account head (every head the operator runs) the reset was
     *  never recorded at all.
     *
     *  An ABSOLUTE instant is preferred over a duration in every branch: it survives clock skew and
     *  it is the fact the operator needs to read — the live muse body says "resets at 20:02:52Z"
     *  where the Retry-After header says only 5301000ms. The body is upstream-controlled text, so
     *  the result is bounded by MAX_PROVIDER_RESET_MS and the caller may always clear() it. */
    public fun captureProviderReset(lift: Lift) {
        // An ABSOLUTE instant arrives on the WALL base and becomes a DELAY here; a duration is already a delay.
        // Mixing the bases is the bug this shape prevents: an epoch millisecond compared against this class's
        // elapsed clock reads as a reset decades out. Since slice 3 the instant arrives READ, by the reply's own
        // reader, which took it from the reset header or the body's own spelling (each vendor's, in its own reader:
        // MuseReplies for Muse); a pushback the Retry-After header asked for is not the provider naming a reset, and
        // arm() already holds that one.
        val at = (lift as? Lift.At)?.takeIf { it.source != LiftSource.RETRY_AFTER } ?: return
        val wallNow = wallClock()
        val delayMs = at.at.toEpochMilli() - wallNow
        if (delayMs <= 0) return
        holds.refusing {
            providerUnavailableUntilMs.accumulateAndGet(clock() + minOf(delayMs, MAX_PROVIDER_RESET_MS)) { c, n ->
                maxOf(c, n)
            }
            holds.providerReset(providerResetAt)
        }
    }

    /** NF-01: remaining armed cooldown (0 when idle) — surfaced so doctor/status views can name
     *  WHY a login is marked limited (NF-10/JW-11 read this). */
    public fun remainingMs(): Long = maxOf(0L, rateLimitedUntilMs.get() - clock())

    /** UP-001's arming site: a retryable status carrying an absurd Retry-After arms the same
     *  horizon a 429 does. Reads the clock at the call, exactly as the inline
     *  `val until = clock() + minOf(...)` it replaced. NF-01: at most MAX_RATE_LIMIT_COOLDOWN_MS — the
     *  full pushback rides in the upstream body the caller's GIVE_UP surfaces; only the
     *  horizon clamps, and the clamp and the latest-max accumulate live in this one place. */
    public fun arm(pushbackMs: Long): Unit = holds.refusing {
        val until = clock() + minOf(pushbackMs, MAX_RATE_LIMIT_COOLDOWN_MS)
        rateLimitedUntilMs.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
    }

    /** A turn left this login on a limit: it moved to another login, or it ended or held (failure-handling slice 4,
     *  where RetryEngine decides which; until slice 5 a hold ends the turn). The provider's own reset is captured, a
     *  plan window it named spent is held until that reset, the native reply is kept for a client that reads it, and
     *  the login is marked limited for steering and status. A pooled login facing a pushback past the floor is also
     *  marked unavailable, so selection moves the next turn at once. Returns the hold the plan window armed, or null.
     *  Re-arming an armed horizon is a max() and costs nothing. */
    internal fun limitLeft(left: LimitLeft, onRetry: RetryNotice): Long? {
        captureProviderReset(left.lift)
        rateLimitReply = left.native
        val planned = left.planLimit?.let { planHold.hold(it, onRetry) }
        val pushback = planned ?: left.pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS
        val header = left.pushbackMs?.let { "${it}ms" } ?: "ABSENT"
        val armed = minOf(pushback, MAX_RATE_LIMIT_COOLDOWN_MS)
        onRetry("429 rate limit: Retry-After header $header, arming ${armed}ms on this login")
        if (left.pooled && (left.pushbackMs ?: 0L) > HOLD_FLOOR_MS) {
            markUnavailable(pushback)
            onRetry("429 Retry-After ${pushback}ms exceeds the same-account wait ceiling; account unavailable")
        }
        arm(pushback)
        return planned
    }
}

/** What a turn that left a login on a limit knew of it: the reset the provider stated ([lift]), the pushback its
 *  Retry-After asked for, the plan window it named spent, its native reply, and whether the login is one of a pool. */
internal data class LimitLeft(
    val lift: Lift,
    val pushbackMs: Long?,
    val planLimit: PlanLimit?,
    val native: NativeReply?,
    val pooled: Boolean,
)

// How long a 429 that carries no Retry-After (the ChatGPT backend's bare
// {"detail":"Rate limit exceeded"}) marks its login limited for steering and status. Short
// enough that a recovered account is chosen again within one client-retry cycle.
internal const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 20_000L

// NF-01: ceiling on the ARMED horizon, whatever the pushback says. ChatGPT quota errors
// legitimately carry multi-day resets (142h observed 2026-07-26) and accumulateAndGet(max)
// makes the longest value ever seen win permanently — one malformed pushback would mark the
// login limited, for steering and status, with no operator escape short of killing the daemon.
// 120s lets a recovered account be chosen again inside one client-retry cycle; the true
// pushback still reaches the operator in the surfaced upstream body.
//
// Internal since the client recheck (Oct 12): the client's deadline is now ClientRecheck's 30 s in :features-turns,
// not this ceiling, so no other module reads it.
internal const val MAX_RATE_LIMIT_COOLDOWN_MS: Long = 120_000L

// A saturated or hostile header must not poison reset reporting after the local re-probe opens.
// Seven days preserves legitimate multi-day resets while bounding the reporting-only state too.
private const val MAX_PROVIDER_RESET_MS = 604_800_000L
