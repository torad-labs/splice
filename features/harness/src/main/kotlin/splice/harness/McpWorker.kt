// NEW: the harness (docs/specs/harness.md, decision 13). A worker that is a tool of an MCP server the hub runs: the
// server is the one the session would load in its folder, found by the name the declaration gives, and the tool is
// called with the declared fields as its arguments. A server with a command is started in the session's folder for the
// call and stopped when it ends; one at a URL (what the hub hosts for every session) is spoken to where it is.
package splice.harness

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.capability.CapabilityWorker
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.nio.file.Path

/** What a call of a tool is sent: its [arguments], and the [meta] that rides beside them in the call's `_meta`. */
internal data class McpToolCall(val arguments: JsonObject, val meta: JsonObject)

internal class McpWorker(
    private val servers: WorkerMcpServers,
    private val client: HttpClient,
    private val io: CoroutineDispatcher,
) {
    /** Calls the tool [worker] names on the server the session of [head] loads in [folder]. */
    suspend fun call(
        worker: CapabilityWorker.Mcp,
        head: String,
        folder: Path?,
        what: McpToolCall,
        progress: WorkerProgress,
    ): WorkerEnd {
        if (folder == null) return WorkerEnd.Failed("its session's folder could not be found")
        val entry = servers.servers(head, folder)[worker.server]
            ?: return WorkerEnd.Failed("the MCP hub has no server ${worker.server} for its session's folder")
        return try {
            opened(entry, folder).use { McpConversation(it).call(worker.tool, what.arguments, what.meta, progress) }
        } catch (failed: McpWireFailure) {
            WorkerEnd.Failed(failed.message.orEmpty())
        } catch (_: IOException) {
            WorkerEnd.Failed("it could not be started")
        }
    }

    /** The wire to the server [entry] declares, started in [folder] when it is a command. */
    private fun opened(entry: JsonObject, folder: Path): McpWire {
        if (JsonWire.string(entry).contains(VARIABLE)) {
            throw McpWireFailure("its entry names an environment variable, which splice does not fill in")
        }
        val type = JsonScalars.str(entry, "type")
        val command = JsonScalars.str(entry, "command")
        val url = JsonScalars.str(entry, "url")
        return when {
            command != null && (type == null || type == "stdio") ->
                McpStdioWire(listOf(command) + strings(entry["args"]), pairs(entry["env"]), folder, io)
            url != null && (type == null || type == "http") -> McpHttpWire(
                client,
                uri(url),
                pairs(entry["headers"]),
                io,
            )
            else -> throw McpWireFailure("its entry is not one splice can speak to")
        }
    }

    private fun uri(url: String): URI = try {
        URI(url)
    } catch (_: URISyntaxException) {
        throw McpWireFailure("its url is not a URL")
    }

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun pairs(element: JsonElement?): Map<String, String> = (element as? JsonObject).orEmpty()
        .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { name to it.content } }
        .toMap()
}

/** why: Claude Code fills `${NAME}` in a server's entry from its own environment, which splice does not read. */
private const val VARIABLE = "\${"
