// NEW: the two native command destinations share a head, never a live folder or selection marker.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.app.head.HeadConfigDirs
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

/** The native default has a home-level account file; the separate command has a directory-local one. */
internal class ClaudeLoginLocations(
    private val home: Path,
    private val paths: StatePaths,
) {
    fun read(topology: Topology): List<ClaudeLoginLocation> {
        val key = ClaudeLoginPlaceId.SPLICE.wire
        val declared = topology.heads[key] ?: return emptyList()
        val stores = paths.stateDir.resolve("claude-logins")
        val separate = HeadConfigDirs.of(key, declared.claude.configDir, home)
        return listOf(
            ClaudeLoginLocation(
                ClaudeLoginPlaceId.NATIVE,
                ClaudeLoginTarget(ClaudeHead(key, home.resolve(UserHome.CLAUDE_DIR)), home.resolve(".claude.json")),
                stores.resolve("native"),
            ),
            ClaudeLoginLocation(
                ClaudeLoginPlaceId.SPLICE,
                ClaudeLoginTarget(ClaudeHead(key, separate), separate.resolve(".claude.json")),
                stores,
            ),
        )
    }
}
