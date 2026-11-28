// NEW: failure-handling slice 5 (2026-10-27). The headers of family L, a limit the session cannot wait out, handed to
// Claude Code as a 429 (final-design.md section 9, "When a hold does not fit"). Claude Code's unattended retry sleeps
// until the plain unified reset or at least the Retry-After, and sends the turn again then
// (http-429-retry-after-30s, http-429-reset-hours-away in the client reaction record). With one login that is the
// true reset. Where another login could serve the head it is RECHECK_SECONDS away, so a login that frees or joins
// meanwhile serves the re-ask, and the true reset stays where a person reads it: the line and the per-window member.
package splice.head.hold

import splice.core.failure.LimitWindow
import splice.core.failure.RECHECK_SECONDS
import splice.core.failure.WireAnswer
import splice.core.usage.FIVE_HOUR_CLAIM
import splice.core.usage.SEVEN_DAY_CLAIM
import splice.core.wire.SHOULD_RETRY_HEADER
import splice.core.wire.WholeSeconds
import splice.upstream.failure.ReplyFacts.RETRY_AFTER_HEADER
import java.util.concurrent.TimeUnit

/** The headers a handed-over limit is answered with. */
internal object HandOverHeaders {
    /** [answer]'s headers at [nowMs] on the wall clock: handed-over limit headers, or Retry-After in whole seconds
     *  rounded up for an overload that carried a provider pushback. */
    fun of(answer: WireAnswer, nowMs: Long): Map<String, String> = when {
        answer.status == null -> emptyMap()
        answer.handedOver -> handoffHeaders(answer, nowMs)
        else -> pushbackHeaders(answer, nowMs)
    }

    private fun handoffHeaders(answer: WireAnswer, nowMs: Long): Map<String, String> {
        val reset = answer.resetsAt?.toEpochMilli()
        val recheckAt = nowMs + TimeUnit.SECONDS.toMillis(RECHECK_SECONDS)
        val toldAt = if (reset == null || answer.recheck) minOf(recheckAt, reset ?: recheckAt) else reset
        val headers = linkedMapOf(
            RETRY_AFTER_HEADER to secondsUntil(toldAt, nowMs).toString(),
            UNIFIED_STATUS to UNIFIED_REJECTED,
            UNIFIED_RESET to WholeSeconds.of(toldAt).toString(),
        )
        if (!answer.recheck && answer.firmReset) windowClaim(answer.window)?.let { headers[UNIFIED_CLAIM] = it }
        val member = windowMember(answer.window)
        if (member != null && reset != null) headers[member] = WholeSeconds.of(reset).toString()
        return headers
    }

    /** All response headers for a non-stream failure: [of]'s reset/pushback headers plus `x-should-retry`. */
    fun all(answer: WireAnswer, nowMs: Long): Map<String, String> {
        val headers = of(answer, nowMs)
        val retry = answer.shouldRetry ?: return headers
        return headers + (SHOULD_RETRY_HEADER to retry.toString())
    }

    private fun pushbackHeaders(answer: WireAnswer, nowMs: Long): Map<String, String> {
        val pushbackAt = answer.retryAfterAt?.toEpochMilli() ?: return emptyMap()
        return mapOf(RETRY_AFTER_HEADER to secondsUntil(pushbackAt, nowMs).toString())
    }

    /** The per-window reset member of [window], the one Claude Code reads that window's reset from. */
    private fun windowMember(window: LimitWindow?): String? = when (window) {
        LimitWindow.FIVE_HOUR -> FIVE_HOUR_RESET
        LimitWindow.WEEKLY, LimitWindow.MODEL_WEEKLY -> SEVEN_DAY_RESET
        LimitWindow.BILLING_CYCLE, LimitWindow.OTHER, null -> null
    }

    /** The unified representative claim of [window], only for whole-login windows whose reset matches [UNIFIED_RESET]. */
    private fun windowClaim(window: LimitWindow?): String? = when (window) {
        LimitWindow.FIVE_HOUR -> FIVE_HOUR_CLAIM
        LimitWindow.WEEKLY -> SEVEN_DAY_CLAIM
        LimitWindow.MODEL_WEEKLY, LimitWindow.BILLING_CYCLE, LimitWindow.OTHER, null -> null
    }

    private fun secondsUntil(atMs: Long, nowMs: Long): Long = WholeSeconds.of(atMs - nowMs)
}

private const val UNIFIED_STATUS = "anthropic-ratelimit-unified-status"
private const val UNIFIED_RESET = "anthropic-ratelimit-unified-reset"
private const val UNIFIED_CLAIM = "anthropic-ratelimit-unified-representative-claim"
private const val FIVE_HOUR_RESET = "anthropic-ratelimit-unified-5h-reset"
private const val SEVEN_DAY_RESET = "anthropic-ratelimit-unified-7d-reset"

/** why: the unified status Claude Code reads a refused limit from. */
private const val UNIFIED_REJECTED = "rejected"
