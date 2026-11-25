// NEW: modes-mcp (Nov 21, 2026) — a launch is told of the MCP servers the daemon answers itself. A hosted server is
// one the person declared, so it reaches a client through their own config, rewritten. A server the daemon answers
// itself (a mode whose kind is mcp) is in nobody's config: the launch names it, in one more `--mcp-config` file.
package splice.client.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.client.ClaudeArgv
import splice.client.Keys
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Path

/** The names of the MCP servers the daemon answers itself for a launch on [head] by [caller] (`door` for the wrapped
 *  `claude`, `head` for a head's own command): the mcp modes that are on there. */
public fun interface OwnMcpServers {
    public fun names(head: String, caller: String): List<String>
}

/** The file of the daemon's own servers for one launch, and the launch's arguments with it among them. Each entry is
 *  the HTTP entry a hosted server gets ([McpEndpoint.entry]), so it carries the MCP-scoped bearer and goes in an
 *  owner-only file in [dir], splice's state folder, never in argv. */
public class OwnMcpLaunch(
    private val servers: OwnMcpServers,
    private val sharing: McpSharing,
    private val dir: Path,
    private val log: LogSink,
) {
    /** [args] with the daemon's own servers for [head] and [caller] added after them, as the one word
     *  `--mcp-config=<file>`, which Claude Code reads beside the launch's other files. As one word it takes nothing
     *  after it as a value: a launch puts it in front of the person's words, which keep the meaning they had, a
     *  prompt and an option missing its value alike, and a relaunch takes it out there with splice's other words
     *  (LaunchRecipeArgs; McpKeyHolders reads it in either spelling). Unchanged when [asked], the person's own words,
     *  say `--strict-mcp-config`, which asks for their own servers alone; when the daemon answers no server for that
     *  launch; or when the file cannot be written: the launch then runs without them, and the log says so. */
    public fun added(args: List<String>, head: String, caller: String, asked: List<String>): List<String> {
        if (ClaudeArgv.options(asked).has(STRICT_MCP_FLAG)) return args
        val file = file(head, caller) ?: return args
        return args + "$MCP_CONFIG_FLAG=$file"
    }

    private fun file(head: String, caller: String): Path? {
        val names = servers.names(head, caller)
        if (names.isEmpty()) return null
        // a launch with no head (`claude` on the door) has a file of its own: the Claude head's names that head
        val named = if (head.isBlank()) caller else "$head-$caller"
        val file = dir.resolve("$OWN_MCP_FILE_PREFIX$named.json")
        return try {
            val body = buildJsonObject {
                putJsonObject(
                    Keys.MCP_SERVERS,
                ) { names.forEach { put(it, sharing.endpoint.entry(it, head.ifBlank { null })) } }
            }
            SecureFile.writeAtomic0600(file, body.toString())
            file
        } catch (unwritable: IOException) {
            log("[mcp] this launch runs without splice's own servers: ${SafeFailureText.render(unwritable)}\n")
            null
        }
    }
}

/** The start of the name of every file of the daemon's own servers: each entry in one is splice's, with its MCP key
 *  (McpKeyHolders). A caller's word holds no `-`, so a launch with no head never shares a head's file name. */
internal const val OWN_MCP_FILE_PREFIX: String = "own-mcp-"

/** Claude Code's switch that runs only the MCP servers the launch's own `--mcp-config` names. */
private const val STRICT_MCP_FLAG = "--strict-mcp-config"
