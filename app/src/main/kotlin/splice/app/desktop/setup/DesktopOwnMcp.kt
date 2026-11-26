// NEW: modes-mcp for Claude Desktop (Nov 25, 2026). The servers the daemon answers itself that Claude Desktop's own
// sessions are given: the mcp modes on for every head for `desktop` (splice.toml's `on`, else the manifest's `on` or
// its `on_for`) and not left out for it by their manifest's `[callers] off`. Only the daemon holds the modes, but
// Desktop's configuration is also written by commands that run without it (`splice mcp sync`, `splice setup`, `splice
// launch claude-desktop`) and its servers are reached through the stdio bridge Desktop starts, a process of its own. So
// each time the daemon reads them for Desktop it records their names in its state folder, and every other process reads
// that record: all of them list the same servers, and a command that cannot see the modes never takes a mode's entry
// out of Desktop's configuration. The record holds names alone, and there is none while no mode is given to Desktop.
package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.client.mcp.OwnMcpServers
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import splice.modes.api.ModeCaller
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The daemon's own servers for Claude Desktop, read from the daemon's modes ([live]) and recorded in [stateDir], or,
 *  where there is no [live], read from that record. */
internal class DesktopOwnMcp(stateDir: Path, private val live: OwnMcpServers? = null) {
    private val record = stateDir.resolve(DESKTOP_OWN_MCP_FILE)

    /** The servers' names, in name order. The daemon's reading is recorded first, so the bridge and every command
     *  that writes Desktop's configuration read the same names; writing the record throws IOException. */
    fun names(): List<String> {
        val modes = live ?: return recorded()
        // Desktop runs no head here: the door picks one per turn, so a mode's every-head value for Desktop decides.
        val names = modes.names("", ModeCaller.DESKTOP.wire)
        if (names != recorded()) write(names)
        return names
    }

    /** The names the daemon last recorded; none when it recorded none or the record can't be read as one. */
    fun recorded(): List<String> {
        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val held = JsonScalars.objectOrNull(Json, Files.readString(record))
        return (held?.get(DESKTOP_OWN_MCP_KEY) as? JsonArray).orEmpty().mapNotNull { JsonScalars.str(it) }
    }

    /** [names] recorded, owner-only; the record taken away when there are none. */
    private fun write(names: List<String>) {
        if (names.isEmpty()) {
            Files.deleteIfExists(record)
            return
        }
        val body = buildJsonObject { put(DESKTOP_OWN_MCP_KEY, JsonArray(names.map(::JsonPrimitive))) }
        SecureFile.writeAtomic0600(record, body.toString())
    }
}

// The record's name in the state folder, and its one key.
private const val DESKTOP_OWN_MCP_FILE = "desktop-own-mcp.json"
private const val DESKTOP_OWN_MCP_KEY = "servers"
