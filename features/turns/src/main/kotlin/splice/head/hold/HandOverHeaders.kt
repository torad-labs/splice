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
import java.util.concurrent.TimeUnit

/** The headers a handed-over limit is answered with. */
internal object HandOverHeaders {
    /** [answer]'s headers at [nowMs] on the wall clock: none unless it is a handed-over status. */
    fun of(answer: WireAnswer, nowMs: Long): Map<String, String> {
        if (!answer.handedOver || answer.status == null) return emptyMap()
        val reset = answer.resetsAt?.toEpochMilli()
        val recheckAt = nowMs + TimeUnit.SECONDS.toMillis(RECHECK_SECONDS)
        val toldAt = if (reset == null || answer.recheck) minOf(recheckAt, reset ?: recheckAt) else reset
        val headers = linkedMapOf(
            RETRY_AFTER to secondsUntil(toldAt, nowMs).toString(),
            UNIFIED_STATUS to UNIFIED_REJECTED,
            UNIFIED_RESET to ceilSeconds(toldAt).toString(),
        )
        val member = windowMember(answer.window)
        if (member != null && reset != null) headers[member] = ceilSeconds(reset).toString()
        return headers
    }

    /** The per-window reset member of [window], the one Claude Code reads that window's reset from. */
    private fun windowMember(window: LimitWindow?): String? = when (window) {
        LimitWindow.FIVE_HOUR -> FIVE_HOUR_RESET
        LimitWindow.WEEKLY, LimitWindow.MODEL_WEEKLY -> SEVEN_DAY_RESET
        LimitWindow.OTHER, null -> null
    }

    private fun secondsUntil(atMs: Long, nowMs: Long): Long = ceilSeconds((atMs - nowMs).coerceAtLeast(0L))

    private fun ceilSeconds(ms: Long): Long = TimeUnit.MILLISECONDS.toSeconds(ms + TimeUnit.SECONDS.toMillis(1L) - 1L)
}

private const val RETRY_AFTER = "retry-after"
private const val UNIFIED_STATUS = "anthropic-ratelimit-unified-status"
private const val UNIFIED_RESET = "anthropic-ratelimit-unified-reset"
private const val FIVE_HOUR_RESET = "anthropic-ratelimit-unified-5h-reset"
private const val SEVEN_DAY_RESET = "anthropic-ratelimit-unified-7d-reset"

/** why: the unified status Claude Code reads a refused limit from. */
private const val UNIFIED_REJECTED = "rejected"
