package splice.client.mcp.names

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Expected names were executed from the installed client's extracted pure Sn function in Bun.
 * Byte offsets, snippet hashes and vectors are in artifacts/session-completion-2026-10-27/claude-mcp-names-source.json. */
class ClaudeMcpToolNamesTest {
    @Test
    fun `ordinary names and punctuation follow the actual client declaration`() {
        assertName("capture", "inspect.dot", "mcp__capture__inspect_dot")
        assertName("server one", "read.file", "mcp__server_one__read_file")
        assertName("already__valid", "under__score-and_123", "mcp__already__valid__under__score-and_123")
    }

    @Test
    fun `unicode replaces each UTF16 code unit as the non-Unicode source regex does`() {
        assertName("Straße 東京", "café", "mcp__Stra_e_____caf_")
        assertName("emoji😀", "face💥", "mcp__emoji____face__")
        assertName("x\uD800y", "\uDC00", "mcp__x_y___")
    }

    @Test
    fun `the exact claude ai prefix collapses and trims underscores independently in both parts`() {
        assertName("claude.ai   foo__bar ", "claude.ai __a___ ", "mcp__claude_ai_foo_bar__claude_ai_a")
        assertName("claude.ai ___", "t", "mcp__claude_ai__t")
        assertName("Claude.ai  X", "hello world", "mcp__Claude_ai__X__hello_world")
        assertName("_claude.ai __", "x", "mcp___claude_ai_____x")
    }

    private fun assertName(server: String, tool: String, expected: String) {
        assertEquals(expected, ClaudeMcpToolNames.name(server, tool))
    }
}
