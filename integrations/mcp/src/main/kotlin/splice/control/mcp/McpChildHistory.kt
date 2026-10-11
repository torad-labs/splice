// NEW: a hosted child's spawn history, split out of HostedServer (2026-10-08) so the crash-loop backoff is one concern with
// one contract: admit() answers whether a spawn may go ahead now, in the words the client is told when it may not.
package splice.control.mcp

import kotlin.time.Duration.Companion.milliseconds

/** A child that lived shorter than this is a crash, and two in a row are a crash loop. */
private const val CRASH_LOOP_MS = 30_000L

// V4-122: MCP_-prefixed because this is the MCP hosted-server's child-restart backoff, and the
// name BACKOFF_BASE_MS was ALSO carried by UpstreamTransport.kt for the retry curve at 200ms — one
// name over two unrelated budgets, which the checker held as a scar because whoever greps the name
// finds the wrong one and tunes the wrong retry. These are two different policies, so the fix is to
// say WHICH one this is, not to make them agree.
private const val MCP_BACKOFF_BASE_MS = 5_000L

// why: the longest a crash loop waits, so a flapping server is retried once a minute rather than never.
private const val MCP_BACKOFF_MAX_MS = 60_000L

// why: 5 s doubled four times is 80 s, past the 60 s cap, so the exponent never needs to go higher.
private const val BACKOFF_MAX_SHIFT = 4

internal class McpChildHistory(private val clock: HostClock, private val name: String) {
    /** Consecutive short-lived children; the second and later wait before respawning. */
    @Volatile private var crashes = 0

    @Volatile private var exitedAt = 0L

    @Volatile var startedAt: Long = 0L
        private set

    @Volatile var restarts: Int = 0
        private set

    /** A crash loop (the last child died young, and so did the one before) waits before the next spawn: 5 s, 10 s, ... 60 s;
     *  calls in between fail in words instead of respawning at once. One crash still respawns immediately: a single failure is
     *  not a loop (review 2026-09-14). Null admits the spawn, and counts it as a restart when a child ran before. */
    fun admit(): McpResult.Refused? {
        val loop = if (exitedAt > 0L && exitedAt - startedAt < CRASH_LOOP_MS) crashes + 1 else 0
        val sinceExit = clock.millis() - exitedAt
        // Bound the exponent before shifting: bounding the shifted result cannot undo Long overflow.
        val shift = (loop - 2).coerceIn(0, BACKOFF_MAX_SHIFT)
        val wait = if (loop > 1) minOf(MCP_BACKOFF_BASE_MS shl shift, MCP_BACKOFF_MAX_MS) - sinceExit else 0L
        if (wait > 0L) {
            val seconds = wait.milliseconds.inWholeSeconds + 1
            val message = "hosted MCP server '$name' keeps crashing ($loop times); next restart in $seconds s"
            return McpResult.Refused(message)
        }
        crashes = loop
        if (startedAt > 0L) restarts += 1
        return null
    }

    /** The child is up. */
    fun started() {
        startedAt = clock.millis()
    }

    /** The child is gone. */
    fun exited() {
        exitedAt = clock.millis()
    }
}
