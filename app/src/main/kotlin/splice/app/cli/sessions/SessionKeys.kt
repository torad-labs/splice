// Key rotation (Oct 26, 2026): whether a running session holds a key of splice's that a rotation strands, for
// `splice sessions relaunch`. Two kinds. The turn key a launch before the key helper planted in ANTHROPIC_AUTH_TOKEN
// counts only when it is one of splice's keys: the management key, the turn key made from it, or one a rotation
// retired (RetiredKeys, PR #247's fourth review). And a session's hosted MCP servers hold the MCP key its launch handed
// it, which counts once a later key was published (McpKeyHolders, PR #247's fifth review). A value is only compared,
// never kept or printed.
package splice.app.cli.sessions

import splice.client.mcp.McpKeyHolders
import splice.core.config.RetiredKeys
import splice.sessions.registry.ProcessEnvironment
import java.nio.file.Path

// The variable a launch before the key helper planted the turn key in.
private const val PLANTED = "ANTHROPIC_AUTH_TOKEN"

internal class SessionKeys(
    private val environment: ProcessEnvironment,
    /** The management key the daemon holds now. */
    private val key: String,
    private val retired: RetiredKeys,
    private val mcp: McpKeyHolders,
    /** The person's home, where a session with no CLAUDE_CONFIG_DIR reads its `.claude.json`. */
    private val home: Path,
) {
    /** What is known of one session: it holds a key a rotation strands, it holds none, or [unread] could not be read. */
    enum class Held(val unread: String? = null) {
        YES,
        NO,
        ENVIRONMENT_UNREAD("the environment"),
        MCP_UNREAD("the MCP configuration"),
    }

    fun of(pid: Long): Held {
        val turn = environment.holds(pid, PLANTED) { retired.ownKey(it, key) }
        if (turn == true) return Held.YES
        val started = environment.started(pid)
        val replaced = mcp.replaced(started, environment.arguments(pid), environment.configHome(pid, home))
        return when {
            replaced == true -> Held.YES
            turn == null -> Held.ENVIRONMENT_UNREAD
            replaced == null -> Held.MCP_UNREAD
            else -> Held.NO
        }
    }
}
