// NEW: the CLIENT CONTRACT of a refusal splice writes itself (V4-72). Claude Code's persistent retry mode
// (CLAUDE_CODE_RETRY_WATCHDOG, which LaunchService now plants for every head) is what lets a session
// survive a limit longer than its default ten retries — and that mode REFUSES to wait when the response sets
// anthropic-ratelimit-unified-overage-disabled-reason (read by Zio() in the 2.1.257 binary). splice authors
// that header set when every login of a pool is exhausted, so a header we did not intend would turn our own
// refusal into a session-ending error. The admission hold this file also pinned is gone (Marcos, Oct 26): a
// turn is never refused for another turn's rate limit, so no wording of that refusal is left to check.
package splice.head.admission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import java.nio.file.Path

class RateLimitRefusalClientContractTest {

    @Test
    fun `the rejected header set never sets the overage-disabled reason`(@TempDir tmp: Path) {
        val tracker = QuotaTracker(tmp.resolve("codex-quota.json"), WallClock { 0L }, LogSink { })

        // POSITIVE FIRST: the refusal must actually be stated, or the absence below is vacuous.
        val blind = tracker.clientHeadersRejected(1_788_030_000L)
        assertEquals("rejected", blind["anthropic-ratelimit-unified-status"], "the refusal is stated")
        assertEquals("1788030000", blind["anthropic-ratelimit-unified-reset"], "with the deadline")

        assertTrue(
            blind.keys.none { it.contains("overage-disabled-reason") },
            "splice must never tell the client its overage is disabled — persistent retry stops on it: $blind",
        )
    }
}
