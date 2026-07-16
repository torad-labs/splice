// NEW: what the control plane needs to manage one head — the :core Head lifecycle handle plus
// the file-based truth sources (auth/usage/compact) it reads DIRECTLY, so a DOWN head still
// shows last-known state (the AGENTS.md contract). :control depends on :core only; :app supplies
// the concrete pieces. Config is one shared in-process service (no PATCH fanout — single JVM).
package splice.control

import splice.core.auth.AuthProvider
import splice.core.head.Head

/** Reads the head's persisted usage/ratelimit (file truth). */
public interface HeadUsageSource {
    public fun outputTokens5h(): Long
    public fun entries(): Int
    public fun ratelimit(): RateLimitView?
}

public data class RateLimitView(val limitTokens: Long?, val remainingTokens: Long?, val resetTokens: String?)

/** Reads the head's compaction stats (file truth). */
public interface HeadCompactSource {
    public fun summary(tailN: Int): CompactView
}

public data class CompactView(val total: Int, val byOutcome: Map<String, Int>, val tail: List<Map<String, String>>)

/** Reads the head's log tail (file truth). */
public interface HeadLogSource {
    public fun tail(lines: Int): String
}

public data class ManagedHead(
    val head: Head,
    val auth: AuthProvider,
    val usage: HeadUsageSource,
    val compact: HeadCompactSource,
    val logs: HeadLogSource,
    val warnPct: Int,
    val warnTokens5h: Long,
)
