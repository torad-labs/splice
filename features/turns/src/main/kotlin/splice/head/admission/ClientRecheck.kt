// NEW: a limit refusal tells the client to come back soon, so a login added meanwhile is used on its next try.
package splice.head.admission

import splice.core.util.WallClock
import splice.core.wire.NativeReply
import splice.upstream.retry.RetryAfter

/** How long a client refused for a limit sleeps before it asks again. */
internal const val CLIENT_RECHECK_SECONDS = 30L

private const val RETRY_AFTER = "retry-after"
private const val UNIFIED_RESET = "anthropic-ratelimit-unified-reset"

/**
 * When a limit refusal tells the client to come back. Claude Code's unattended retry sleeps until the plain
 * `anthropic-ratelimit-unified-reset` (up to six hours) or at least the `retry-after`, and nothing wakes it sooner:
 * on Oct 12 nine sessions slept two hours on a spent weekly window while a second login sat free. So every limit
 * answer splice writes names a deadline at most [CLIENT_RECHECK_SECONDS] away, and each retry re-checks the pool at
 * admission, where a held login costs no upstream request. The real reset stays where a person reads it: in the
 * message text, the per-window `-5h-reset` and `-7d-reset` members, and the perf row (V4-46).
 */
internal class ClientRecheck(private val wallClock: WallClock = WallClock(System::currentTimeMillis)) {
    /** The client's deadline for a refusal whose real reset is [resetEpochSeconds], or a recheck when none is known. */
    fun deadline(resetEpochSeconds: Long?): Long {
        val recheck = wallClock() / MILLIS_PER_SECOND + CLIENT_RECHECK_SECONDS
        return resetEpochSeconds?.let { minOf(it, recheck) } ?: recheck
    }

    /** The provider's own 429, with its two retry deadlines brought within the recheck and every other byte kept. */
    fun reply(reply: NativeReply): NativeReply {
        val now = wallClock()
        return reply.copy(
            headers = reply.headers.mapValues { (name, values) ->
                when {
                    name.equals(RETRY_AFTER, ignoreCase = true) -> values.map { retryAfter(it, now) }
                    name.equals(UNIFIED_RESET, ignoreCase = true) -> values.map { unifiedReset(it, now) }
                    else -> values
                }
            },
        )
    }

    private fun retryAfter(value: String, now: Long): String {
        val ms = RetryAfter().retryAfterMs(value, WallClock { now }) ?: return value
        return if (ms > CLIENT_RECHECK_SECONDS * MILLIS_PER_SECOND) CLIENT_RECHECK_SECONDS.toString() else value
    }

    private fun unifiedReset(value: String, now: Long): String {
        val reset = value.trim().toDoubleOrNull() ?: return value
        val recheck = now / MILLIS_PER_SECOND + CLIENT_RECHECK_SECONDS
        return if (reset > recheck) recheck.toString() else value
    }
}
