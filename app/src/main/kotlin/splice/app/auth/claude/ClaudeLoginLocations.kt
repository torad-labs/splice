// NEW: where the Claude head's one own login lives: Claude Code's own home.
package splice.app.auth.claude

import splice.accounts.claude.CLAUDE_LOGIN_HEAD
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.topology.Topology
import java.nio.file.Path

/** Native files and splice's safe-copy store for one command. Paths are explicit so tests never read a live login. */
internal data class ClaudeLoginLocation(
    val id: ClaudeLoginPlaceId,
    val target: ClaudeLoginTarget,
    val storeDir: Path,
) {
    val credentials: Path get() = target.head.configDir.resolve(".credentials.json")
}

/** Claude Code keeps its login in ~/.claude and its account record in the home-level ~/.claude.json. */
internal class ClaudeLoginLocations(
    private val home: Path,
    private val paths: StatePaths,
) {
    fun read(topology: Topology): List<ClaudeLoginLocation> {
        if (CLAUDE_LOGIN_HEAD !in topology.heads) return emptyList()
        val own = ClaudeHead(CLAUDE_LOGIN_HEAD, home.resolve(UserHome.CLAUDE_DIR))
        return listOf(
            ClaudeLoginLocation(
                ClaudeLoginPlaceId.NATIVE,
                ClaudeLoginTarget(own, home.resolve(".claude.json")),
                paths.stateDir.resolve("claude-logins").resolve("native"),
            ),
        )
    }
}
