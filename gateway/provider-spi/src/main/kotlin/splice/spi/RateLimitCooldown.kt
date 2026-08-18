// PORT-OF: splice/spi/UpstreamClient.kt (rateLimitedUntilMs and every one of its touch points) @ 3879c4c — invariants unchanged: NF-01's clamp and escape hatch, latest-max arming, and the fail-fast 429 body byte-for-byte.
//
// The shared 429 cooldown horizon (HD-25). One UpstreamClient per head = per upstream account, so
// one turn's rate-limit discovery teaches every concurrent turn, which then fails fast with 429
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
// WALL: dev/campaigns/proxy-hardening/walls/nf_01_rate_limit_cooldown_bounded.py reads THIS file.
//
// BLOCKED destination, recorded so it is not re-proposed: gateway/gateway/.../usage/RateLimitStore.kt
// is in :gateway, and :provider-spi depends only on :core — that edge would invert.
package splice.spi

import splice.core.util.ElapsedClock
import java.util.concurrent.atomic.AtomicLong

/**
 * [clock] is the SAME instance UpstreamClient was constructed with, never a second one: the horizon
 * and the retry deadline must not split-brain across clock bases (review 2026-07-22). It is read
 * HERE rather than passed in as a `nowMs` so that every read happens at the instruction the
 * original did — an arming site that reads the clock before a token refresh instead of after it
 * shortens the armed horizon by the refresh's duration.
 */
internal class RateLimitCooldown(private val clock: ElapsedClock) {
    // Armed by any attempt that observes a 429; while armed, every post() fails fast with a
    // synthesized 429 and ZERO upstream calls. Benign write race: concurrent arms only differ by
    // ms; latest-max wins.
    private val rateLimitedUntilMs = AtomicLong(0L)

    /** NF-01: head restart is a real escape hatch — HeadServer.startLocked() clears the armed
     *  horizon alongside driver.resetHealth(), instead of the cooldown outliving the restart. */
    fun clear() {
        rateLimitedUntilMs.set(0L)
    }

    /** NF-01: remaining armed cooldown (0 when idle) — surfaced so doctor/status views can name
     *  WHY a head is failing fast (NF-10/JW-11 read this). */
    fun remainingMs(): Long = maxOf(0L, rateLimitedUntilMs.get() - clock())

    /** UP-001's arming site: a retryable status carrying an absurd Retry-After arms the same
     *  horizon a 429 does. Reads the clock at the call, exactly as the inline
     *  `val until = clock() + minOf(...)` it replaced. */
    fun arm(pushbackMs: Long): Unit = armAt(clock(), pushbackMs)

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
    fun failFastIfArmed(onRetry: RetryNotice) {
        val remainingMs = rateLimitedUntilMs.get() - clock()
        if (remainingMs <= 0) return
        onRetry("rate-limit cooldown active (${remainingMs}ms remaining) — failing fast, no upstream attempt")
        val waitS = (remainingMs + MS_PER_S - 1) / MS_PER_S
        throw UpstreamFailed(
            """{"detail":"Rate limit exceeded — gateway cooldown, retry in ${waitS}s"}""",
            RATE_LIMITED,
        )
    }

    /** A real 429 teaches the whole head: arm the shared cooldown so concurrent turns fail fast
     *  instead of each burning their own attempts into the same limited account. */
    fun rateLimitedPlan(
        pushbackMs: Long?,
        onRetry: RetryNotice,
        nextRefreshed: Boolean,
    ): RetryPlan {
        // Read BEFORE the ceiling notice, exactly as the `clock()` argument this replaced was
        // evaluated before the call it sat in.
        val nowMs = clock()
        val pushback = pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS
        if (pushback > MAX_RATE_LIMIT_COOLDOWN_MS) {
            onRetry(
                "429 Retry-After ${pushback}ms exceeds the cooldown ceiling — " +
                    "arming ${MAX_RATE_LIMIT_COOLDOWN_MS}ms",
            )
        }
        armAt(nowMs, pushback)
        // A concurrent wave can receive 429 before any member sees the shared cooldown.
        // Retrying each member would amplify one upstream limit into N×maxRetries requests;
        // terminate every observed 429 and let the client retry after the shared horizon.
        return RetryPlan(RetryDecision.GIVE_UP, nextRefreshed)
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
