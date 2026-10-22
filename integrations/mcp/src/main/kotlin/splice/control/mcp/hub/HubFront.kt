// NEW: MCP hub — the front's persisted routing view, read before a shared holder may be swept.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val ROUTES = "routes"
private const val SESSIONS = "sessions"
private const val HOLDERS = "holders"
private const val DRAINS = "drains"
private const val SERVER = "server"
private const val SOCKET = "socket"
private const val LAUNCH = "launch"
private const val GENERATION = "generation"
private const val CANDIDATE_SOCKET = "candidate_socket"
private const val CANDIDATE_GENERATION = "candidate_generation"
private const val SHARED = "shared"
private const val LAST_CALL = "last_call"

/** One route the front retains: its server alias, launch, holder socket, and shared-routing declaration. */
internal data class HubRoute(
    val server: String,
    val launch: String,
    val socket: String,
    val shared: Boolean,
)

/** One session the front holds: its server alias, holder socket, and launch. */
internal data class HubBinding(val server: String, val socket: String, val launch: String)

/** One exact holder generation held by a persisted front replacement drain. */
internal data class HubDrainHolder(val socket: String, val generation: String)

/** What the front saved: retained routes, sessions, drain holders, and each holder's last call. */
internal data class HubReport(
    val routes: List<HubRoute>,
    val bindings: List<HubBinding>,
    val draining: Set<HubDrainHolder>,
    val lastCalls: Map<String, Long>,
)

internal class HubFront(private val file: Path) {
    private val reader = HubFrontReader(file)
    private val reports = HubFrontReports()

    /** What the front last saved, or null when it saved no readable route state. */
    fun report(): HubReport? = reader.read()?.let(reports::report)
}

private class HubFrontReader(private val file: Path) {
    fun read(): JsonObject? = try {
        file.takeIf(Files::isRegularFile)?.let { JsonRpcCodec().parse(Files.readString(it)) }
    } catch (_: IOException) {
        null
    }
}

private class HubFrontReports {
    fun report(saved: JsonObject): HubReport? = routes(saved)?.let { routes ->
        HubReport(routes, bindings(saved), draining(saved), lastCalls(saved))
    }

    private fun routes(saved: JsonObject): List<HubRoute>? = (saved[ROUTES] as? JsonObject)?.values?.mapNotNull(::route)

    private fun route(value: kotlinx.serialization.json.JsonElement): HubRoute? =
        (value as? JsonObject)?.let { saved ->
            val server = JsonScalars.str(saved, SERVER)
            val socket = JsonScalars.str(saved, SOCKET)
            if (server != null && socket != null) {
                HubRoute(
                    server,
                    JsonScalars.str(saved, LAUNCH).orEmpty(),
                    socket,
                    JsonScalars.str(saved, SHARED) != "false",
                )
            } else {
                null
            }
        }

    private fun bindings(saved: JsonObject): List<HubBinding> {
        val routes = saved[ROUTES] as? JsonObject ?: return emptyList()
        val sessions = saved[SESSIONS] as? JsonObject ?: return emptyList()
        return sessions.keys.mapNotNull { session -> binding(session, sessions, routes) }
    }

    private fun binding(session: String, sessions: JsonObject, routes: JsonObject): HubBinding? =
        JsonScalars.str(sessions, session)?.let { routes[it] as? JsonObject }?.let { route ->
            val server = JsonScalars.str(route, SERVER)
            val socket = JsonScalars.str(route, SOCKET)
            if (server != null && socket != null) {
                HubBinding(server, socket, JsonScalars.str(route, LAUNCH).orEmpty())
            } else {
                null
            }
        }

    private fun draining(saved: JsonObject): Set<HubDrainHolder> = (saved[DRAINS] as? JsonObject)
        ?.values
        ?.flatMap(::drainHolders)
        ?.toSet()
        .orEmpty()

    private fun drainHolders(value: kotlinx.serialization.json.JsonElement): List<HubDrainHolder> =
        (value as? JsonObject)?.let { drain ->
            listOfNotNull(
                drainHolder(drain, SOCKET, GENERATION),
                drainHolder(drain, CANDIDATE_SOCKET, CANDIDATE_GENERATION),
            )
        }.orEmpty()

    private fun drainHolder(drain: JsonObject, socket: String, generation: String): HubDrainHolder? =
        JsonScalars.str(drain, socket)?.let { name ->
            JsonScalars.str(drain, generation)?.let { version -> HubDrainHolder(name, version) }
        }

    private fun lastCalls(saved: JsonObject): Map<String, Long> = (saved[HOLDERS] as? JsonObject)
        ?.mapNotNull { (socket, holder) ->
            JsonScalars.long(holder as? JsonObject, LAST_CALL)?.let { socket to it }
        }
        ?.toMap()
        .orEmpty()
}
