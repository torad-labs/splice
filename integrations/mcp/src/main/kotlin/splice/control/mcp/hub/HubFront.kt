// NEW: MCP hub — the front's persisted routing view, read before a shared holder may be swept.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val LAST_CALL = "last_call"
private const val UNPINNING = "unpinning"

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
    val claims: Set<HubDrainClaim>,
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

/** Malformed ownership is not an empty drain set: destructive sweeps require every retained identity to decode. */
private class HubDrainChecks {
    fun readable(saved: JsonObject): Boolean {
        val value = saved[HubFields.DRAINS] ?: return true
        return (value as? JsonObject)?.values?.all { drain ->
            (drain as? JsonObject)?.let(::valid) == true
        } == true
    }

    private fun valid(drain: JsonObject): Boolean {
        val oldFields = listOf(HubFields.SERVER, HubFields.SOCKET, HubFields.GENERATION, HubFields.PHASE)
        val old = oldFields.all { JsonScalars.str(drain, it)?.isNotEmpty() == true }
        val candidate = listOf(
            JsonScalars.str(drain, HubFields.CANDIDATE_SOCKET),
            JsonScalars.str(drain, HubFields.CANDIDATE_GENERATION),
            JsonScalars.str(drain, HubFields.CANDIDATE_TUPLE),
        )
        val absent = candidate.all { it == null }
        val complete = candidate.all { !it.isNullOrEmpty() }
        return old && (absent || complete)
    }
}

private class HubFrontReports {
    private val drains = HubDrainChecks()

    fun report(saved: JsonObject): HubReport? = if (drains.readable(saved)) {
        routes(saved)?.let { routes ->
            HubReport(routes, bindings(saved), draining(saved), claims(saved), lastCalls(saved))
        }
    } else {
        null
    }

    private fun routes(saved: JsonObject): List<HubRoute>? =
        (saved[HubFields.ROUTES] as? JsonObject)?.values?.map(::route)
            ?.takeUnless { routes -> routes.any { it == null } }
            ?.filterNotNull()

    private fun route(value: kotlinx.serialization.json.JsonElement): HubRoute? =
        (value as? JsonObject)?.let { saved ->
            val server = JsonScalars.str(saved, HubFields.SERVER)
            val socket = JsonScalars.str(saved, HubFields.SOCKET)
            if (server != null && socket != null) {
                HubRoute(
                    server,
                    JsonScalars.str(saved, HubFields.LAUNCH).orEmpty(),
                    socket,
                    JsonScalars.str(saved, HubFields.SHARED) != "false",
                )
            } else {
                null
            }
        }

    private fun bindings(saved: JsonObject): List<HubBinding> {
        val routes = saved[HubFields.ROUTES] as? JsonObject ?: return emptyList()
        val sessions = saved[HubFields.SESSIONS] as? JsonObject ?: return emptyList()
        return sessions.keys.mapNotNull { session -> binding(session, sessions, routes) }
    }

    private fun binding(session: String, sessions: JsonObject, routes: JsonObject): HubBinding? =
        JsonScalars.str(sessions, session)?.let { routes[it] as? JsonObject }?.let { route ->
            val server = JsonScalars.str(route, HubFields.SERVER)
            val socket = JsonScalars.str(route, HubFields.SOCKET)
            if (server != null && socket != null) {
                HubBinding(server, socket, JsonScalars.str(route, HubFields.LAUNCH).orEmpty())
            } else {
                null
            }
        }

    private fun draining(saved: JsonObject): Set<HubDrainHolder> = (saved[HubFields.DRAINS] as? JsonObject)
        ?.values
        ?.flatMap(::drainHolders)
        ?.toSet()
        .orEmpty()

    private fun claims(saved: JsonObject): Set<HubDrainClaim> = (saved[HubFields.DRAINS] as? JsonObject)
        ?.values
        ?.mapNotNull { value -> (value as? JsonObject)?.let(::claim) }
        ?.toSet()
        .orEmpty()

    private fun claim(drain: JsonObject): HubDrainClaim? =
        drain.takeUnless { JsonScalars.str(it, HubFields.PHASE) == UNPINNING }
            ?.let(::claimFields)

    private fun claimFields(drain: JsonObject): HubDrainClaim? {
        val server = JsonScalars.str(drain, HubFields.SERVER)
        val launch = JsonScalars.str(drain, HubFields.LAUNCH)
        val socket = JsonScalars.str(drain, HubFields.SOCKET)
        val generation = JsonScalars.str(drain, HubFields.GENERATION)
        val claim = JsonScalars.str(drain, HubFields.OWNER_CLAIM)
        return if (listOf(server, launch, socket, generation, claim).any { it == null }) {
            null
        } else {
            HubDrainClaim(
                HubDrainPinRequest(server.orEmpty(), launch.orEmpty(), socket.orEmpty(), generation.orEmpty()),
                claim.orEmpty(),
            )
        }
    }

    private fun drainHolders(value: kotlinx.serialization.json.JsonElement): List<HubDrainHolder> =
        (value as? JsonObject)?.takeUnless { JsonScalars.str(it, HubFields.PHASE) == UNPINNING }?.let { drain ->
            listOfNotNull(
                drainHolder(drain, HubFields.SOCKET, HubFields.GENERATION),
                drainHolder(drain, HubFields.CANDIDATE_SOCKET, HubFields.CANDIDATE_GENERATION),
            )
        }.orEmpty()

    private fun drainHolder(drain: JsonObject, socket: String, generation: String): HubDrainHolder? =
        JsonScalars.str(drain, socket)?.let { name ->
            JsonScalars.str(drain, generation)?.let { version -> HubDrainHolder(name, version) }
        }

    private fun lastCalls(saved: JsonObject): Map<String, Long> = (saved[HubFields.HOLDERS] as? JsonObject)
        ?.mapNotNull { (socket, holder) ->
            JsonScalars.long(holder as? JsonObject, LAST_CALL)?.let { socket to it }
        }
        ?.toMap()
        .orEmpty()
}
