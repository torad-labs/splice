// PORT-OF: splice/spi/UpstreamClient.kt (rateLimitedUntilMs and every one of its touch points) @ 3879c4c — invariants unchanged: NF-01's clamp and escape hatch, latest-max arming, and the fail-fast 429 body byte-for-byte.
//
// The shared 429 cooldown horizon (HD-25). Each pooled account owns one; legacy heads use the
// UpstreamClient's instance. One turn's rate-limit discovery teaches concurrent turns on that
// account, which then fail fast with 429
// instead of independently burning its own attempts (2026-07-19 storm: ~650 turns x 4 attempts
// against one limited account).
//
// This is SHARED MUTABLE STATE, and that is exactly why every touch point moved together rather
// than the state being split from its rules: the AtomicLong, the arming, the clamp, the fail-fast
// exit, the doctor read and the restart escape hatch are all here, and nothing outside this file
// names the horizon. The arming idiom used to be written TWICE — statusPlan's UP-001 arm and
// rateLimitedPlan's 429 arm each spelled out `clock() + minOf(pushback, MAX)` followed by
// `accumulateAndGet(max)` — and is now [arm], called from both.
//
// WALL: .dev/campaigns/proxy-hardening/walls/nf_01_rate_limit_cooldown_bounded.py reads THIS file.
//
// BLOCKED destination, recorded so it is not re-proposed: gateway/gateway/.../usage/RateLimitStore.kt
// is in :gateway, and :provider-spi depends only on :core — that edge would invert.
package splice.spi

import java.util.concurrent.atomic.AtomicLong

/**
 * [clock] is the SAME instance UpstreamClient was constructed with, never a second one: the horizon
 * and the retry deadline must not split-brain across clock bases (review 2026-07-22). It is read
 * inside the arming method, after any preceding retry notice and token refresh, rather than
 * captured before refresh and passed in. Notice callbacks may advance a test clock too; the
 * cooldown starts when it is armed, not when the response headers were first received.
 */
public class RateLimitCooldown public constructor(private val clock: ElapsedNow) {
    // Armed by any attempt that observes a 429; while armed, every post() fails fast with a
    // synthesized 429 and ZERO upstream calls. Benign write race: concurrent arms only differ by
    // ms; latest-max wins.
    private val rateLimitedUntilMs = AtomicLong(0L)
    private val unavailableUntilMs = AtomicLong(0L)
    private val providerUnavailableUntilMs = AtomicLong(0L)

    /** NF-01: head restart is a real escape hatch — HeadServer.startLocked() clears the armed
     *  horizon alongside driver.resetHealth(), instead of the cooldown outliving the restart. */
    public fun clear() {
        rateLimitedUntilMs.set(0L)
    }

    /** Account-pool restart escape hatch; separate so NF-01's legacy [clear] wall stays exact. */
    internal fun clearUnavailable() {
        unavailableUntilMs.set(0L)
        providerUnavailableUntilMs.set(0L)
    }

    /** Marks the account unavailable to future turns, bounded by NF-01's recovery ceiling. */
    public fun markUnavailable(pushbackMs: Long) {
        val now = clock()
        val providerDelay = pushbackMs.coerceIn(0L, MAX_PROVIDER_RESET_MS)
        val boundedDelay = minOf(providerDelay, MAX_RATE_LIMIT_COOLDOWN_MS)
        // The elapsed-clock seam may be near Long.MAX_VALUE even though the delay is capped.
        val providerDelayLimit = Long.MAX_VALUE - now
        val boundedUntil = now + boundedDelay
        val providerUntil = now + minOf(providerDelay, providerDelayLimit)
        unavailableUntilMs.accumulateAndGet(boundedUntil) { current, candidate -> maxOf(current, candidate) }
        providerUnavailableUntilMs.accumulateAndGet(providerUntil) { current, candidate -> maxOf(current, candidate) }
    }

    /** Remaining bounded account-selection cooldown (0 when the account may be selected). */
    public fun unavailableForMs(): Long = maxOf(0L, unavailableUntilMs.get() - clock())

    /** Provider reset for reporting, capped at seven days; never used to keep an account unavailable. */
    internal fun providerUnavailableForMs(): Long = maxOf(0L, providerUnavailableUntilMs.get() - clock())

    /** NF-01: remaining armed cooldown (0 when idle) — surfaced so doctor/status views can name
     *  WHY a head is failing fast (NF-10/JW-11 read this). */
    public fun remainingMs(): Long = maxOf(0L, rateLimitedUntilMs.get() - clock())

    /** UP-001's arming site: a retryable status carrying an absurd Retry-After arms the same
     *  horizon a 429 does. Reads the clock at the call, exactly as the inline
     *  `val until = clock() + minOf(...)` it replaced. */
    public fun arm(pushbackMs: Long): Unit = armAt(clock(), pushbackMs)

    /** NF-01: arm at most MAX_RATE_LIMIT_COOLDOWN_MS — the full pushback is not lost, it rides in
     *  the upstream body the caller's GIVE_UP surfaces; only the fail-fast horizon clamps. The
     *  clamp and the latest-max accumulate are ONE method because they were two copies, and a
     *  clamp that one copy forgets is how a multi-day pushback poisons a head permanently. */
    private fun armAt(nowMs: Long, pushbackMs: Long) {
        val until = nowMs + minOf(pushbackMs, MAX_RATE_LIMIT_COOLDOWN_MS)
        rateLimitedUntilMs.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
    }

    /** The cooldown's fail-fast exit: a synthesized 429 (classifier parity with the real one)
     *  thrown BEFORE credentials/attempt work — an armed cooldown costs microseconds, not an
     *  upstream request. The remaining wait rides in the message for the operator's grep. */
    public fun failFastIfArmed(onRetry: RetryNotice) {
        val remainingMs = rateLimitedUntilMs.get() - clock()
        if (remainingMs <= 0) return
        onRetry("rate-limit cooldown active (${remainingMs}ms remaining) — failing fast, no upstream attempt")
        val waitS = (remainingMs + MS_PER_S - 1) / MS_PER_S
        throw UpstreamFailed(
            """{"detail":"Rate limit exceeded — gateway cooldown, retry in ${waitS}s"}""",
            RATE_LIMITED,
        )
    }

    /** A waitable pooled 429 keeps the selected account and arms only its local follower horizon:
     *  concurrent sessions fail fast during that short wait instead of switching to an idle backup.
     *  Non-pooled turns preserve the legacy one-attempt exit. Only a provider-supplied wait beyond
     *  the fixed interactive ceiling removes an account from later selection; an exhausted turn budget
     *  cannot evict a healthy account. Local 429 protection is not provider quota. */
    internal fun rateLimitedPlan(
        pushbackMs: Long?,
        turn: RateLimitTurn,
        canRetry: Boolean,
        onRetry: RetryNotice,
        nextRefreshed: Boolean,
    ): RetryPlan {
        val pushback = pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS
        noticeClamp(pushback, onRetry)
        val plannedDelayMs = maxOf(pushback, turn.backoffCeilingMs)
        val fitsTurn = plannedDelayMs < turn.remainingBudgetMs
        val fitsInteractive = plannedDelayMs <= RETRY_AFTER_GIVE_UP_MS
        val retrySameAccount = turn.pooledAccount && canRetry
        val delayFits = fitsTurn && fitsInteractive
        if (retrySameAccount && delayFits) {
            arm(pushback)
            onRetry("429 backoff up to ${plannedDelayMs}ms fits the remaining turn budget; retrying same account")
            return RetryPlan(RetryDecision.BACKOFF, nextRefreshed, pushback)
        }
        val providerWaitExceeded = pushbackMs != null && pushback > RETRY_AFTER_GIVE_UP_MS
        if (turn.pooledAccount && providerWaitExceeded) {
            markUnavailable(pushback)
            onRetry("429 Retry-After ${pushback}ms exceeds the same-account wait ceiling; account unavailable")
        }
        arm(pushback)
        return RetryPlan(RetryDecision.GIVE_UP, nextRefreshed)
    }

    private fun noticeClamp(pushbackMs: Long, onRetry: RetryNotice) {
        if (pushbackMs <= MAX_RATE_LIMIT_COOLDOWN_MS) return
        onRetry(
            "429 Retry-After ${pushbackMs}ms exceeds the cooldown ceiling; " +
                "arming ${MAX_RATE_LIMIT_COOLDOWN_MS}ms follower protection",
        )
    }
}

internal const val RATE_LIMITED = 429

// Cooldown length when a 429 carries no Retry-After (the ChatGPT backend's bare
// {"detail":"Rate limit exceeded"}). Long enough to starve a herd, short enough that a
// recovered account resumes within one client-retry cycle.
private const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 20_000L

// NF-01: ceiling on the ARMED horizon, whatever the pushback says. ChatGPT quota errors
// legitimately carry multi-day resets (142h observed 2026-07-26) and accumulateAndGet(max)
// makes the longest value ever seen win permanently — one malformed pushback would poison
// the head for every future turn with no operator escape short of killing the daemon.
// 120s starves a herd but lets a recovering account resume inside one client-retry cycle;
// the true pushback still reaches the operator in the surfaced upstream body.
private const val MAX_RATE_LIMIT_COOLDOWN_MS = 120_000L

// A saturated or hostile header must not poison reset reporting after the local re-probe opens.
// Seven days preserves legitimate multi-day resets while bounding the reporting-only state too.
private const val MAX_PROVIDER_RESET_MS = 604_800_000L
