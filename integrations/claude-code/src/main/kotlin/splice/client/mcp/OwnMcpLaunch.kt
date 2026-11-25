// NEW: modes-mcp (Nov 21, 2026) — a launch is told of the MCP servers the daemon answers itself. A hosted server is
// one the person declared, so it reaches a client through their own config, rewritten. A server the daemon answers
// itself (a mode whose kind is mcp) is in nobody's config: the launch names it, in one more `--mcp-config` file.
package splice.client.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
 *  owner-only file in [dir], splice's state folder, never in argv; it also names the launch's caller, which the mode is
 *  told. */
public class OwnMcpLaunch(
    private val servers: OwnMcpServers,
    private val sharing: McpSharing,
    private val dir: Path,
    private val log: LogSink,
) {
    /** [args] with the daemon's own servers for [head] and [caller] added: a `--mcp-config` of their own after
     *  whatever the launch already carries, which Claude Code reads beside it (the flag takes several files, and each
     *  use of it adds to them). A flag and a file of its own keeps the pair every reader of a launch's arguments
     *  looks for (LaunchRecipeArgs, McpKeyHolders). Unchanged when the daemon answers no server for that launch, or
     *  the file cannot be written: the launch then runs without them, and the log says so. */
    public fun added(args: List<String>, head: String, caller: String): List<String> =
        file(head, caller)?.let { args + listOf(MCP_CONFIG_FLAG, it.toString()) } ?: args

    private fun file(head: String, caller: String): Path? {
        val names = servers.names(head, caller)
        if (names.isEmpty()) return null
        val file = dir.resolve("own-mcp-${head.ifBlank { "claude" }}-$caller.json")
        return try {
            val body = buildJsonObject {
                putJsonObject(
                    Keys.MCP_SERVERS,
                ) { names.forEach { put(it, sharing.endpoint.entry(it, head.ifBlank { null }, caller = caller)) } }
            }
            SecureFile.writeAtomic0600(file, body.toString())
            file
        } catch (unwritable: IOException) {
            log("[mcp] this launch runs without splice's own servers: ${SafeFailureText.render(unwritable)}\n")
            null
        }
    }
}
