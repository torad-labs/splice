// Key rotation (Oct 26, 2026): whether a running session holds a key of splice's that a rotation strands, for
// `splice sessions relaunch`. Two kinds. The turn key a launch before the key helper planted in ANTHROPIC_AUTH_TOKEN
// counts only when it is one of splice's keys: the management key, the turn key made from it, or one a rotation
// retired (RetiredKeys, PR #247's fourth review). And a session's hosted MCP servers hold the MCP key its launch handed
// it, which counts once a later key was published (McpKeyHolders, PR #247's fifth review). A value is only compared,
// never kept or printed.
//
// PR #247 review round 3 (Oct 30): a launch also writes the turn key into ANTHROPIC_CUSTOM_HEADERS, as the launch proof
// a head holding logins of its own asks for, and nothing refreshes it there. A proof a rotation retired counts; the
// current key does not, since every fresh launch holds it and the command would never run out of sessions to relaunch.
package splice.app.cli.sessions

import splice.client.mcp.McpKeyHolders
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.config.RetiredKeys
import splice.sessions.registry.ProcessEnvironment
import java.nio.file.Path

// The variable a launch before the key helper planted the turn key in.
private const val PLANTED = "ANTHROPIC_AUTH_TOKEN"

// The variable a launch writes the launch proof in, one header per line.
private const val CUSTOM_HEADERS = "ANTHROPIC_CUSTOM_HEADERS"

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
        val proof = environment.holds(pid, CUSTOM_HEADERS) { headers -> headers.lines().any(::retiredProof) }
        if (turn == true || proof == true) return Held.YES
        val started = environment.started(pid)
        val replaced = mcp.replaced(started, environment.arguments(pid), environment.configHome(pid, home))
        return when {
            replaced == true -> Held.YES
            turn == null -> Held.ENVIRONMENT_UNREAD
            replaced == null -> Held.MCP_UNREAD
            else -> Held.NO
        }
    }

    /** Whether [line] is the launch proof header naming a turn key a rotation retired. */
    private fun retiredProof(line: String): Boolean =
        line.substringBefore(':').trim().equals(LAUNCH_PROOF_HEADER, ignoreCase = true) &&
            retired.holds(line.substringAfter(':').trim())
}
