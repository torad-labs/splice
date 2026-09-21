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
// V4-47 (2026-09-16): the provider's own reset is CAPTURED here and NAMED to the operator, but the
// fail-fast horizon is deliberately NOT extended to it. THAT DECISION IS LOAD-BEARING — do not
// "simplify" the re-probe away, and do not read the cycling as waste. Two reasons, hardest first.
//  1. NF-01's wall pins clear()'s complete body to exactly rateLimitedUntilMs.set(0L). Restart is
//     this file's ONLY escape hatch, so a fail-fast gate must live on the one horizon clear() can
//     reach. Gate it on a horizon restart cannot clear and you rebuild the permanent poisoning
//     NF-01 exists to prevent, with no operator escape short of killing the daemon.
//  2. The bounded re-probe is what DETECTS THE OPERATOR TOPPING UP. Extending the horizon to the
//     provider reset makes a head ignore a restored quota for hours — the head would refuse to try
//     the very fix the message asks him to apply. One upstream request per two minutes, on a head
//     that cannot serve him anyway, is a cheap price for noticing the moment it can.
//
// BLOCKED destination, recorded so it is not re-proposed: gateway/gateway/.../usage/RateLimitStore.kt
// is in :gateway, and :upstream depends only on :core — that edge would invert.
package splice.upstream.retry

import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.ErrorEnvelope
import splice.core.wire.HttpStatus
import splice.upstream.RetryNotice
import splice.upstream.transport.UpstreamFailed
import java.time.Instant
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
) {
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

    /** Provider reset for reporting, capped at seven days; never used to keep an account unavailable.
     *  PUBLIC since V4-50: the admission plane reads it to put a real deadline on the wire, which is
     *  the only place a client can act on it. Still never gates selection. */
    public fun providerUnavailableForMs(): Long = maxOf(0L, providerUnavailableUntilMs.get() - clock())

    /** V4-47: capture the provider's own reset from the 429 body, at ARM time and NOT gated on
     *  pooledAccount — markUnavailable is the only other writer and it fires only for pooled turns
     *  over the 15s ceiling, so on a single-account head (every head the operator runs) the reset was
     *  never recorded at all.
     *
     *  An ABSOLUTE instant is preferred over a duration in every branch: it survives clock skew and
     *  it is the fact the operator needs to read — the live muse body says "resets at 20:02:52Z"
     *  where the Retry-After header says only 5301000ms. The body is upstream-controlled text, so
     *  the result is bounded by MAX_PROVIDER_RESET_MS and the caller may always clear() it. */
    private fun captureProviderReset(body: String?) {
        if (body == null) return
        val epochSeconds = RESET_EPOCH_RE.find(body)?.groupValues?.get(1)?.toLongOrNull()
        val iso = RESET_ISO_RE.find(body)?.groupValues?.get(1)
        val inSeconds = RESET_IN_RE.find(body)?.groupValues?.get(1)?.toLongOrNull()
        // An absolute instant arrives on the WALL base and becomes a DELAY here; `resets_at` is the
        // ChatGPT backend's epoch-seconds spelling of the same fact, and a duration is already a
        // delay. Mixing the bases is the bug this shape prevents: an epoch millisecond compared
        // against this class's elapsed clock reads as a reset decades out.
        // The ISO branch collapses to null DELIBERATELY. It parses a timestamp out of an
        // UPSTREAM-CONTROLLED 429 body on a best-effort basis: the epoch-seconds spelling above and
        // the duration below are the other two candidates, and the caller already treats "no reset
        // was named" as a first-class outcome — the refusal simply ships without a Retry-After and
        // leaves the client its own backoff. A vendor emitting a malformed instant is not a splice
        // failure to diagnose, and logging one per 429 would be noise on exactly the path an
        // operator is already reading.
        val absoluteWallMs = epochSeconds?.times(MS_PER_S)
            // ast-grep-ignore: kt-no-silent-result-collapse -- best-effort vendor timestamp; null is the complete story, see above
            ?: iso?.let { Cancellables.runCatchingCancellable { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val delayMs = absoluteWallMs?.minus(wallClock()) ?: inSeconds?.times(MS_PER_S) ?: return
        if (delayMs <= 0) return
        providerUnavailableUntilMs.accumulateAndGet(clock() + minOf(delayMs, MAX_PROVIDER_RESET_MS)) { c, n ->
            maxOf(c, n)
        }
    }

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
     *  upstream request. The remaining wait rides in the message for the operator's grep.
     *
     *  V4-46: the body names the GATEWAY's interval and stops there, deliberately. This turn never
     *  reached upstream, so it cannot know the provider's reset; the old wording ("gateway cooldown,
     *  retry in Ns") read as an instruction to retry in Ns, which invited a retry that cannot
     *  succeed while crowding out the real cause (a weekly quota wall resets in days, not seconds).
     *  Two quantities had one sentence. Carrying the provider reset forward is a separate change —
     *  it must be captured at ARM time, the only place both the 429 body and this cooldown are in
     *  scope — so this message claims nothing it cannot support. */
    public fun failFastIfArmed(onRetry: RetryNotice) {
        val remainingMs = rateLimitedUntilMs.get() - clock()
        if (remainingMs <= 0) return
        onRetry("rate-limit cooldown active (${remainingMs}ms remaining) — failing fast, no upstream attempt")
        val waitS = (remainingMs + MS_PER_S - 1) / MS_PER_S
        val gatewayClause = "this gateway is holding retries for ${waitS}s"
        val providerResetMs = providerUnavailableForMs()
        // V4-47: when the provider reset is KNOWN, name it and say plainly that the gateway interval
        // is not a retry schedule. The live episode: Retry-After 5301000ms clamped to 120s, body
        // reset 20:02:52Z hours away, and the operator retried three times on a countdown that could
        // never satisfy him. The gateway clause stays in BOTH branches — it is the property V4-46
        // guarantees and a test pins it.
        val detail = if (providerResetMs > 0) {
            // WALL base, not elapsed: providerResetMs is a DELAY, and printing it against the
            // elapsed clock would name a 1970-era instant to the operator.
            val resetsAt = Instant.ofEpochMilli(wallClock() + providerResetMs)
            // V4-61: the window is REPORTED, not asserted as the deadline. muse stamps its 5h-window
            // reset on burst 429s that clear in seconds (the operator's own re-send succeeded), so
            // "waiting will not help" was a claim this turn could not support.
            "Rate limit exceeded — $gatewayClause; the upstream reports its quota window resets " +
                "at $resetsAt. If this keeps happening, that is the real deadline."
        } else {
            "Rate limit exceeded — $gatewayClause to avoid a retry wave"
        }
        // V4-61: the ANTHROPIC ERROR ENVELOPE, not a hand-built {"detail":...}. This body is the
        // classifier's structured input (TurnKnownEnd hands it to UpstreamFailureClassifier, which
        // lifts error.message) and the presentation seam's (FailureText unwraps the same field) —
        // so a failure splice synthesizes is shape-identical to one a real upstream sends, and our
        // sentence reaches the operator as a sentence. The detail key was the one shape neither
        // reader looked at, which is how our own words landed in his transcript as braces.
        // V4-102: the same envelope the gateway sends, built by the same builder in core. This is
        // the site that made the builder live in core rather than in the gateway's wire package —
        // :upstream cannot import :gateway, so our own fail-fast body was free to drift from the
        // shape the classifier reads. It no longer can.
        val body = ErrorEnvelope.of("rate_limit_error", detail).toString()
        throw UpstreamFailed(body, HttpStatus.TOO_MANY_REQUESTS)
    }

    /** Every 429 with retry budget left is WAITED OUT and retried here in splice — a short
     *  Retry-After honored as the floor, anything longer or absent at the 15s ceiling (V4-61). Two
     *  cases terminate instead: an exhausted budget, and a POOLED account facing a wait past the
     *  ceiling, which is evicted so the client's own retry lands on a healthy backup at once. Both
     *  arm the shared local horizon so followers fail fast rather than reproducing the limit. A
     *  short or missing wait never evicts an account and never invents a provider reset. */
    internal fun rateLimitedPlan(
        pushbackMs: Long?,
        turn: RateLimitTurn,
        canRetry: Boolean,
        onRetry: RetryNotice,
        nextRefreshed: Boolean,
        /** V4-47: the 429 body. The provider's own reset lives here ("resets at <ISO8601>",
         *  resets_at, resets_in_seconds) and a fail-fast turn never reaches upstream, so this is
         *  the ONLY point where that fact and this cooldown are both in scope. */
        body: String? = null,
    ): RetryPlan {
        // V4-47: capture BEFORE the plan is built, and NOT gated on pooledAccount. markUnavailable
        // below is the only other writer and it fires only for pooled turns over the 15s ceiling,
        // so on a single-account head — every head the operator runs — the provider reset was never
        // recorded at all.
        // Kept as ONE call rather than inlined: rateLimitedPlan is at detekt's complexity ceiling, and
        // this class is near its function budget, so the capture shares the parser rather than
        // adding a second member.
        captureProviderReset(body)

        // V4-61: a pooled account facing a wait past the interactive ceiling is EVICTED rather than
        // retried — a healthy backup beats three more attempts on a spent account, and the client's
        // own retry lands on that backup at once. Everything else with budget left backs off and
        // retries HERE, in splice, whatever the header said.
        val providerWaitExceeded = pushbackMs != null && pushbackMs > RETRY_AFTER_GIVE_UP_MS
        val evictInstead = turn.pooledAccount && providerWaitExceeded
        if (!canRetry || evictInstead) return giveUpAndArm(pushbackMs, turn, nextRefreshed, onRetry)
        return backoffPlan(pushbackMs, nextRefreshed, onRetry)
    }

    // V4-61: EVERY 429 WITH BUDGET LEFT BACKS OFF AND RETRIES IN SPLICE. V4-48 restored this for a
    // Retry-After at or under the 15s ceiling; an ABSENT or LONG header still gave up, and that is
    // the case the operator actually hits. muse stamps its 5h-window reset on a burst 429 — the
    // live episode carried Retry-After 5301000ms — and his own re-send moments later SUCCEEDED.
    // The long number is the window, not this 429's retry-after, and trusting it as a deadline was
    // wrong in both directions: splice gave up here, then (V4-50) told the client to sleep 88
    // minutes for a limit that cleared in seconds. This is the "retry every 15 seconds" layer the
    // operator remembers: a short header is honored as the floor, anything else waits the ceiling.
    // The fixed 15s dominates the 200ms-doubling curve (capped 10s) that applyBackoff maxes it
    // against, so the schedule reads 15s, 15s, 15s across the default 4-attempt budget — 45s of
    // patience before the client ever sees a 429, against a 900s turn timeout.
    //
    // WHY THE HERD ARGUMENT DOES NOT BLOCK THIS. The give-up was built against a synchronized
    // wave amplifying one 429 into N x maxRetries requests. The 5xx path has ALWAYS carried the
    // identical exposure and it has been accepted since before v0.3.0; the client's own
    // concurrency is the real ceiling on N, and the operator has chosen the wave over the dead
    // turn, with the evidence on his side. What is NOT bounded away, and is worth naming: a 429 is
    // likelier than a 5xx to hit every concurrent turn at once, so this trade is at its worst on a
    // busy head.
    //
    // ARMING IS DELIBERATELY NOT DONE ON THIS PATH. Arming on the first 429 would fail every
    // OTHER turn on the head for the whole interval this one is waiting out — clamped to 120s on
    // an 88-minute header — while this turn may well succeed at 15s. The horizon is armed once,
    // on exhaustion, by giveUpAndArm.
    private fun backoffPlan(pushbackMs: Long?, nextRefreshed: Boolean, onRetry: RetryNotice): RetryPlan {
        val floor = pushbackMs?.coerceAtMost(RETRY_AFTER_GIVE_UP_MS) ?: RETRY_AFTER_GIVE_UP_MS
        val header = pushbackMs?.let { "${it}ms" } ?: "ABSENT"
        onRetry("429 rate limit: Retry-After header $header; budget remaining, retrying in ${floor}ms")
        return RetryPlan(RetryDecision.BACKOFF, nextRefreshed, minDelayMs = floor)
    }

    /** The give-up half: the V4-46 instrument line and the clamp notice, an optional
     *  pooled-account eviction, the arm, and the terminal plan. Reached only on an exhausted budget
     *  or a pooled eviction (V4-61). Extracted from rateLimitedPlan so each half stays under
     *  detekt's complexity ceiling. */
    private fun giveUpAndArm(
        pushbackMs: Long?,
        turn: RateLimitTurn,
        nextRefreshed: Boolean,
        onRetry: RetryNotice,
    ): RetryPlan {
        val pushback = pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS
        val header = pushbackMs?.let { "${it}ms" } ?: "ABSENT"
        onRetry(
            "429 rate limit: Retry-After header $header, " +
                "arming ${minOf(pushback, MAX_RATE_LIMIT_COOLDOWN_MS)}ms follower protection",
        )
        noticeClamp(pushback, onRetry)
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

// Cooldown length when a 429 carries no Retry-After (the ChatGPT backend's bare
// {"detail":"Rate limit exceeded"}). Long enough to starve a herd, short enough that a
// recovered account resumes within one client-retry cycle.
internal const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 20_000L

// NF-01: ceiling on the ARMED horizon, whatever the pushback says. ChatGPT quota errors
// legitimately carry multi-day resets (142h observed 2026-07-26) and accumulateAndGet(max)
// makes the longest value ever seen win permanently — one malformed pushback would poison
// the head for every future turn with no operator escape short of killing the daemon.
// 120s starves a herd but lets a recovered account resume inside one client-retry cycle;
// the true pushback still reaches the operator in the surfaced upstream body.
//
// V4-100: PUBLIC, and that is the point of the ceiling rather than a widening for its own sake.
// The admission plane hands the client a deadline derived from the same number (HeadAdmission's
// clamp on the Retry-After it writes, and the CLAMP_SECONDS both head tests pin), so the ceiling
// is a CROSS-MODULE fact with three existing readers in :gateway. It lived private here and was
// re-typed there, which is the copy this export retires: one number, one declaration, and the
// client's deadline cannot outlive the cooldown the gateway is actually holding.
public const val MAX_RATE_LIMIT_COOLDOWN_MS: Long = 120_000L

// A saturated or hostile header must not poison reset reporting after the local re-probe opens.
// Seven days preserves legitimate multi-day resets while bounding the reporting-only state too.
private const val MAX_PROVIDER_RESET_MS = 604_800_000L

// V4-47: the provider reset's three spellings, tried in that order of authority. `resets_at` is
// epoch SECONDS (the ChatGPT backend); the ISO form is what the muse body carries; `resets_in_seconds`
// is a duration and therefore the weakest, since it is only as good as our clock at receipt.
private val RESET_EPOCH_RE = Regex(""""resets_at"\s*:\s*(\d{9,})""")
private val RESET_ISO_RE = Regex("""resets?\s+at\s+(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?)""")
private val RESET_IN_RE = Regex(""""resets?_(?:in_seconds|in)"\s*:\s*(\d+)""")
