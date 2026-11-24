// NEW: desktop-3p-parity (Nov 23, 2026). `splice mcp sync --to claude` for Claude Desktop on the gateway: Claude Code
// sessions load Splice's MCP servers through the launch overlay, but Desktop's Chat and Cowork read them only from
// the gateway profile's `claude_desktop_config.json`. So each gateway profile on this machine (`Claude-3p` in Desktop's
// config folder, and in each folder beside it where a second Desktop keeps one) is given them as DesktopProfileSync
// gives them at `splice desktop on` and each daemon start. A profile Desktop has not made is not made.
package splice.app.mcp

import splice.app.desktop.setup.DesktopProfileSync
import splice.app.desktop.setup.SpliceDesktopMcp
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** What one gateway profile's sync came to: [changed] it, or [failed] with the kind of failure. */
internal data class McpDesktopProfile(val profile: Path, val changed: Boolean, val failed: String? = null)

internal class McpDesktopSync(
    private val home: Path,
    private val paths: StatePaths,
    private val env: EnvReader,
    private val osName: String,
) {
    /** Every gateway profile given Splice's MCP servers. */
    fun sync(): List<McpDesktopProfile> {
        val sync = DesktopProfileSync(env, osName, home, mcp = SpliceDesktopMcp(env, home, paths))
        val found = try {
            profiles()
        } catch (failure: IOException) {
            return listOf(McpDesktopProfile(configBase(), changed = false, failed = failure::class.simpleName))
        }
        return found.map { profile ->
            try {
                McpDesktopProfile(profile, sync.syncConfigInto(profile))
            } catch (failure: IOException) {
                McpDesktopProfile(profile, changed = false, failed = failure::class.simpleName)
            }
        }
    }

    /** The gateway profiles on this machine: Desktop's own and each beside it, where Desktop made one. */
    fun profiles(): List<Path> {
        val base = configBase()
        val beside = if (Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(base).use { stream -> stream.limit(DESKTOP_MAX_BESIDE).toList() }.sorted()
        } else {
            emptyList()
        }
        return (listOf(base) + beside).map { it.resolve(GATEWAY_PROFILE) }
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
    }

    /** The folder Desktop keeps its config folders in, as Electron reads it (DesktopAppConfig). */
    private fun configBase(): Path = if (osName.startsWith("Mac", ignoreCase = true)) {
        home.resolve("Library").resolve("Application Support")
    } else {
        env(XDG_CONFIG_VARIABLE)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".config")
    }
}

private const val XDG_CONFIG_VARIABLE = "XDG_CONFIG_HOME"

// The folder Desktop on a gateway keeps its profile in.
private const val GATEWAY_PROFILE = "Claude-3p"

// why: bounds the listing of the config folder, which holds one folder per program the person runs (a few hundred
// at most); a second Desktop's folder is among them.
private const val DESKTOP_MAX_BESIDE = 2_000L
