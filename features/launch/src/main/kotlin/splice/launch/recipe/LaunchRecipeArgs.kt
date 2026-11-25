// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — what the person gave a launch, read back from the
// arguments its client runs with. A relaunch starts a session on the command it was started with, and the launch that
// command makes adds splice's own words again, so only the person's are carried: splice's would arrive twice, and a
// hub file named for the old launch's process is swept once that process is gone.
//
// WHAT A LAUNCH ADDS, in the order LaunchService.launchOutcome writes it after the binary: --dangerously-skip-
// permissions when the launch asked for it (kept, since the session was started with it); the --settings overlay of a
// launch through the wrapped `claude`, or splice's own mark on macOS (LaunchMarker); then the MCP hub's file for the
// launch, or a wrapped launch's own file, as one --mcp-config in splice's state, with a --plugin-dir-no-mcp for each
// plugin the hub runs. Everything after that is the person's, and so is the --effort a launch adds there (Oct 28): a
// relaunch starts at the effort its session started at, and adds none of its own beside it. The servers splice gives
// every launch are splice's too, wherever a build put them: the file of the daemon's own servers (an mcp mode that is
// on) and the peer server, each now the one word `--mcp-config=<value>` in front, the peer's after the binary (Nov 26).
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.client.mcp.HUB_PLUGIN_FLAG
import splice.client.mcp.MCP_CONFIG_FLAG
import splice.client.mcp.OWN_MCP_FILE_PREFIX
import splice.client.mcp.PEER_MCP_SERVER
import splice.sessions.registry.LaunchMarker
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val DANGEROUSLY_SKIP = "--dangerously-skip-permissions"

// why: the key an MCP config names its servers under, read to tell the peer server's inline config
private const val RELAUNCH_SERVERS_KEY = "mcpServers"

/** The words a launch's client was given by the person, after what splice's launch put before them. */
public object LaunchRecipeArgs {
    private val marked = "\"${LaunchMarker.SPLICE_LAUNCH_KEY}\":true"

    /** The person's words among [arguments], the client's arguments after its binary: what follows the launch's own
     *  --settings ([wrapped]: it went through the wrapped `claude`, whose overlay that is) and each --mcp-config file
     *  in splice's [state], with the plugin folders after it, less the servers splice gives every launch wherever a
     *  build of splice put them. A --dangerously-skip-permissions the launch put first stays. */
    public fun callerArgs(arguments: List<String>, state: Path, wrapped: Boolean): List<String> {
        val launched = arguments.filterNot { splicesWord(it, state) }
        val skip = launched.take(1).filter { it == DANGEROUSLY_SKIP }
        var at = skip.size
        val settings = launched.getOrNull(at + 1)
        // the launch's own overlay: a wrapped launch's, whatever it holds, or the mark splice adds on macOS
        val ours = settings != null && (wrapped || marked in settings)
        if (launched.getOrNull(at) == LaunchMarker.SETTINGS_FLAG && ours) at += 2
        // each of the launch's own: the hub's file with its plugin folders, then the file of splice's own servers
        while (launched.getOrNull(at) == MCP_CONFIG_FLAG && inState(launched.getOrNull(at + 1), state)) {
            at += 2
            while (launched.getOrNull(at) == HUB_PLUGIN_FLAG && at + 1 < launched.size) at += 2
        }
        return skip + withoutServers(launched.drop(at), state)
    }

    /** Whether [word] is one of the servers splice gives every launch as the one word `--mcp-config=<value>`, which a
     *  launch puts in front: taking it out strands no other word. */
    private fun splicesWord(word: String, state: Path): Boolean =
        word.startsWith("$MCP_CONFIG_FLAG=") && splices(word.substringAfter('='), state)

    /** [words], the person's, without the servers the build of Nov 25 put after them as a flag and its value: the file
     *  of the daemon's own servers and the peer server's inline config. A flag stays when a word of the person's
     *  follows its value: the flag is then that word's. The launch the relaunch makes names each again in front while
     *  it applies, and none once not. */
    private fun withoutServers(words: List<String>, state: Path): List<String> {
        val kept = mutableListOf<String>()
        var at = 0
        while (at < words.size) {
            if (words[at] == MCP_CONFIG_FLAG && splices(words.getOrNull(at + 1), state)) {
                if (words.getOrNull(at + 2)?.startsWith("-") == false) kept += words[at]
                at += 2
            } else {
                kept += words[at++]
            }
        }
        return kept
    }

    /** Whether [value], given to `--mcp-config`, is one of the servers splice gives every launch: the daemon's own
     *  servers' file in [state], or the inline config of the peer server alone. Any other is the person's. */
    private fun splices(value: String?, state: Path): Boolean {
        val own = inState(value, state) && Path.of(value.orEmpty()).fileName.toString().startsWith(OWN_MCP_FILE_PREFIX)
        return own || value?.trimStart()?.startsWith("{") == true && peerOnly(value)
    }

    /** Whether [json] is an MCP config whose one server is splice's peer server. */
    private fun peerOnly(json: String): Boolean = try {
        val servers = Json.parseToJsonElement(json).jsonObject[RELAUNCH_SERVERS_KEY] as? JsonObject
        servers?.keys == setOf(PEER_MCP_SERVER)
    } catch (_: IllegalArgumentException) {
        // not JSON, or not an object: kotlinx's SerializationException is one
        false
    }

    /** Whether [value] is a file in splice's [state], where a launch writes its MCP files; any other is the person's. */
    private fun inState(value: String?, state: Path): Boolean = try {
        val under = state.toAbsolutePath().normalize()
        value != null && Path.of(value).let { it.isAbsolute && it.normalize().startsWith(under) }
    } catch (_: InvalidPathException) {
        false
    }
}
