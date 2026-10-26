// Key rotation (Oct 26, 2026; PR #247's fifth review): which running sessions hold an MCP key splice no longer accepts.
// A launch hands Claude Code each hosted MCP server as an entry carrying the MCP key published then: in the
// `--mcp-config` file it names (the hub's launch file, or the wrapped launch's file of its head, both in splice's
// state), or among the entries splice writes into its head's `.claude.json`. Claude Code keeps what it read at start,
// and that key stops working once the key is replaced: by `splice rotate-key`, which revokes the MCP key bound to the
// management key, or by a rotation of the MCP key alone. A head's file is rewritten at each launch, so what it says now
// is what the next session gets, not what a running one holds: that is the key published when it started. No key is
// read. An entry is splice's by the header splice writes beside its bearer (X-Splice-Head, or the hub's
// X-Splice-Launch), and the key's age is its publication file's, which is written only when the key changes
// (McpAccessPublication).
package splice.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.client.Keys
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

private const val MCP_CONFIG_FLAG = "--mcp-config"
private const val AUTHORIZATION_HEADER = "Authorization"

/** Whether a running session's hosted MCP servers hold an MCP key that was replaced after the session started. */
public class McpKeyHolders(stateDir: Path) {
    private val state = stateDir.toAbsolutePath().normalize()
    private val publication = McpAccessPublication(stateDir).file

    /** True when the session [started] before the MCP key in force was published and a file it was launched with
     *  carries an entry splice wrote: an `--mcp-config` file in splice's state among its [arguments], or the
     *  `.claude.json` of the [configHome] it runs under (its CLAUDE_CONFIG_DIR, or the person's home when that is
     *  unset). False when it holds none, null when that could not be read. A file of the person's own is not read: a
     *  relaunch would not refresh it. */
    public fun replaced(started: Instant?, arguments: List<String>?, configHome: Path?): Boolean? {
        val before = startedBefore(started)
        if (before != true) return before
        val launched = arguments?.let(::launchFiles)?.map { carries(it, missing = null) } ?: listOf(null)
        // a `.claude.json` can run to megabytes, and a launch file that answers is enough
        if (true in launched) return true
        val read = launched + configHome?.let { carries(it.resolve(Keys.CLAUDE_JSON), missing = false) }
        return when {
            true in read -> true
            null in read -> null
            else -> false
        }
    }

    /** Whether [started] is before the MCP key in force was published: false when no key ever was, or when it started
     *  since; null when either is not known. */
    private fun startedBefore(started: Instant?): Boolean? {
        val published = try {
            Files.getLastModifiedTime(publication, NOFOLLOW_LINKS).toInstant()
        } catch (_: NoSuchFileException) {
            return false
        } catch (_: IOException) {
            return null
        }
        return started?.isBefore(published)
    }

    /** The `--mcp-config` files among [arguments] that a launch of splice's wrote: those in its state. A value of
     *  inline JSON, or a file anywhere else, is the person's own. */
    private fun launchFiles(arguments: List<String>): List<Path> = arguments.mapIndexedNotNull { at, argument ->
        when {
            argument == MCP_CONFIG_FLAG -> arguments.getOrNull(at + 1)
            argument.startsWith("$MCP_CONFIG_FLAG=") -> argument.removePrefix("$MCP_CONFIG_FLAG=")
            else -> null
        }
    }.mapNotNull(::inState)

    private fun inState(value: String): Path? = try {
        Path.of(value).takeIf { it.isAbsolute }?.normalize()?.takeIf { it.startsWith(state) }
    } catch (_: InvalidPathException) {
        null
    }

    /** Whether [file] carries an entry splice wrote: [missing] when there is no file, null when it can't be read. */
    private fun carries(file: Path, missing: Boolean?): Boolean? = try {
        val servers = Json.parseToJsonElement(Files.readString(file)).jsonObject[Keys.MCP_SERVERS] as? JsonObject
        servers?.values?.any(::spliceWrote) ?: false
    } catch (_: NoSuchFileException) {
        missing
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        // not JSON, or not an object: kotlinx's SerializationException is one
        null
    }

    /** An entry splice wrote: its bearer beside the header that names the head, or the hub launch, it was written for. */
    private fun spliceWrote(entry: JsonElement): Boolean {
        val headers = (entry as? JsonObject)?.get("headers") as? JsonObject ?: return false
        return AUTHORIZATION_HEADER in headers && (MCP_HEAD_HEADER in headers || HUB_LAUNCH_HEADER in headers)
    }
}
