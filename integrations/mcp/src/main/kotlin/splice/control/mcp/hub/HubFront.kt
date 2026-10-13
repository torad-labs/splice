// NEW: MCP hub — what the front reports about the sessions it holds, read by the sweep before it stops a shared
// holder. The front keeps its routing table in <state>/mcp-hub/front.json (splice-config's Hub.save): "routes", keyed
// "<server>\n<launch>", each naming its holder's "socket" and the session's "launch"; and "sessions", each naming its
// route's key. A holder is bound while a session's route names its socket.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** One session the front holds: the socket of the holder it is bound to, and the launch it came from ("" unknown). */
internal data class HubBinding(val socket: String, val launch: String)

internal class HubFront(private val file: Path) {
    /** What the front last saved, or null when it saved nothing readable: the sweep then cannot tell who is bound. */
    fun bindings(): List<HubBinding>? {
        val saved = read() ?: return null
        val routes = saved["routes"] as? JsonObject
        val sessions = saved["sessions"] as? JsonObject ?: JsonObject(emptyMap())
        return sessions.keys.mapNotNull { id ->
            val route = JsonScalars.str(sessions, id)?.let { routes?.get(it) as? JsonObject }
            JsonScalars.str(route, "socket")?.let { HubBinding(it, JsonScalars.str(route, "launch").orEmpty()) }
        }
    }

    private fun read(): JsonObject? = try {
        file.takeIf(Files::isRegularFile)?.let { JsonRpcCodec().parse(Files.readString(it)) }
    } catch (_: IOException) {
        null
    }
}
