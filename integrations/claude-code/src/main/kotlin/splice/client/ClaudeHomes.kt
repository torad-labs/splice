// NEW: V4-146 (2026-09-20) — where the readers in McpSources.kt look: every Claude Code identity
// on this box, and the `plugins` tree each one owns. Split out of McpSources.kt (concentration)
// because "which directories exist" is a different concern from "what a file says".
package splice.client

import splice.client.mcp.GIT_DIR
import splice.client.mcp.PLUGINS_DIR
import splice.client.mcp.PLUGIN_WALK_DEPTH
import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** [path]'s real filesystem identity, or itself when that cannot be resolved (missing, denied) —
 *  collapses the operator's SHARED `plugins` symlink (ClaudeConfigKeys.sharedLinkItems: a head may
 *  share it into the global `~/.claude/plugins`) across many `.claude*` roots into the one tree it
 *  actually names, so the census counts a plugin's files once, not once per head that links to it. */
internal class RealPathOrSelf {
    operator fun invoke(path: Path): Path = Cancellables.runCatchingCancellable { path.toRealPath() }.getOrElse { path }
}

/** Every Claude Code identity on this box. Two different roots, because the vanilla identity is
 *  spelled irregularly: its `.claude.json` STATE FILE sits beside `.claude` at `$HOME` directly
 *  (`$HOME/.claude.json`, a historical quirk — never inside `.claude/`), while its CLAUDE_CONFIG_DIR
 *  (agents, commands, plugins, …) is `.claude` itself; every SUFFIXED identity carries both under
 *  the one directory `$HOME/.claude-<suffix>` (verified on this box: `~/.claude-bonsai/.claude.json`
 *  is a real, independent file, not a symlink). Named off Keys.CLAUDE, never a `.claude` literal of
 *  its own (kt-no-vanilla-config-dir scopes that wall to this package; the constant is the one
 *  dispositioned spelling). */
internal class ClaudeHomes(private val homeParent: Path) {
    /** Every `.claude*` CLAUDE_CONFIG_DIR, the vanilla `.claude` included — the root each
     *  identity's OWN generated trees (plugins among them) sit under. */
    fun configDirs(): List<Path> {
        val siblings = Cancellables.runCatchingCancellable {
            Files.newDirectoryStream(homeParent).use { stream ->
                stream.filter { Files.isDirectory(it) && it.name.startsWith(Keys.CLAUDE) }
            }
        }.getOrElse { emptyList() }
        return siblings.sortedBy { it.toString() }
    }

    /** Every root a `.claude.json` state file can sit under: `$HOME` for the vanilla identity, each
     *  suffixed CLAUDE_CONFIG_DIR for the rest. */
    fun roots(): List<Path> =
        (listOf(homeParent) + configDirs().filter { it.name != Keys.CLAUDE }).sortedBy { it.toString() }

    fun claudeJsonFiles(): List<Path> = roots().map { it.resolve(Keys.CLAUDE_JSON) }
}

/** Every distinct `plugins` directory across every Claude identity's CLAUDE_CONFIG_DIR
 *  (deduplicated by real path — see [RealPathOrSelf]), the common root PLUGIN_MCP_JSON and
 *  PLUGIN_INLINE both walk from. */
internal class PluginRoots(homeParent: Path) {
    private val homes = ClaudeHomes(homeParent)
    private val realPath = RealPathOrSelf()

    fun roots(): List<Path> {
        val candidates = homes.configDirs().map { it.resolve(PLUGINS_DIR) }.filter { Files.isDirectory(it) }
        return candidates.distinctBy { realPath(it) }.sortedBy { it.toString() }
    }
}

/** Whether a walked path is the manifest a plugin reader is looking for — named for the ROLE (which
 *  manifest shape a kind reads), never for the raw `(Path) -> Boolean` shape (kt-no-lambda-seam). */
internal fun interface PluginFileMatch {
    operator fun invoke(path: Path): Boolean
}

/** Shared by both plugin readers: a bounded, regular-file walk that never MATCHES inside a `.git`
 *  clone (the marketplace mirrors under `plugins/marketplaces` and `plugins/cache` carry real git
 *  history, not plugin manifests — matching a file there would be counting the plugin's own source
 *  control, not what Claude Code loads). */
internal class PluginFileWalk {
    operator fun invoke(root: Path, matches: PluginFileMatch): List<Path> = Cancellables.runCatchingCancellable {
        Files.walk(root, PLUGIN_WALK_DEPTH).use { stream -> stream.filter { candidate(it, matches) }.toList() }
    }.getOrElse { emptyList() }

    private fun candidate(path: Path, matches: PluginFileMatch): Boolean =
        Files.isRegularFile(path) && matches(path) && path.none { part -> part.name == GIT_DIR }
}
