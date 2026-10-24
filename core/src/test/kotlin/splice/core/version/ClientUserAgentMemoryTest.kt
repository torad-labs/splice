// NEW: the whole Claude Code User-Agent this daemon has seen, which the Claude quota probes present to Anthropic's
// usage endpoint (operator ruling, Oct 4, 12:00 AM CT). It is remembered rather than assembled, because an identity
// splice made up would be a claim about a client that never spoke.
package splice.core.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val OLDER = "claude-cli/2.1.100 (external, cli)"
private const val NEWER = "claude-cli/2.1.289 (external, cli)"

class ClientUserAgentMemoryTest {

    @Test
    fun `a daemon that has seen no client names none`() {
        assertNull(ClientVersionTracker(testedVersion = "2.1.0").newestClaudeCodeUserAgent())
    }

    @Test
    fun `the newest Claude Code's whole User-Agent is what the probe presents`() {
        val tracker = ClientVersionTracker(testedVersion = "2.1.0")

        tracker.observe("s-1", OLDER)
        tracker.observe("s-2", NEWER)

        assertEquals(NEWER, tracker.newestClaudeCodeUserAgent(), "the whole header, not a version splice rebuilt")
    }

    @Test
    fun `an older client arriving later does not take the identity back`() {
        val tracker = ClientVersionTracker(testedVersion = "2.1.0")

        tracker.observe("s-1", NEWER)
        tracker.observe("s-2", OLDER)

        assertEquals(NEWER, tracker.newestClaudeCodeUserAgent())
    }

    @Test
    fun `another tool's User-Agent never becomes the one splice presents`() {
        val tracker = ClientVersionTracker(testedVersion = "2.1.0")

        tracker.observe("s-1", NEWER)
        tracker.observe("s-2", "curl/8.5.0")
        tracker.observe("s-3", "some-other-cli/9.9.9")

        assertEquals(NEWER, tracker.newestClaudeCodeUserAgent())
    }
}
