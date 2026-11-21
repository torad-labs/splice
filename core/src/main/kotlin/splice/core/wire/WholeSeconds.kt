// NEW: desktop-full (docs/specs/desktop-full.md, df-wire-answers). A wait splice tells a client, in whole seconds rounded
// up: a Retry-After or a reset time on Anthropic's wire is an integer, and rounding down would tell a client to come
// back before the time it names. One rule, so every header that carries a wait rounds it the same way.
package splice.core.wire

import java.util.concurrent.TimeUnit

public object WholeSeconds {
    /** [ms] milliseconds as whole seconds, rounded up; never below zero. */
    public fun of(ms: Long): Long {
        val positive = ms.coerceAtLeast(0L)
        return TimeUnit.MILLISECONDS.toSeconds(positive + TimeUnit.SECONDS.toMillis(1L) - 1L)
    }
}
