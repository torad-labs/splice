package splice.control.mcp.hub

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** An unbound unresolved name owns no generation; malformed or bound ownership still fails closed. */
internal class HubFrontRoutes {
    fun read(saved: JsonObject): List<HubRoute>? {
        val routes = saved[HubFields.ROUTES] as? JsonObject ?: return null
        val sessions = saved[HubFields.FRONT_STATE_SESSIONS] as? JsonObject ?: return null
        val bound = sessions.keys.mapNotNull { JsonScalars.str(sessions, it) }.toSet()
        return routes.filterNot { (key, value) -> pending(key, value, bound) }
            .values.map(::route)
            .takeUnless { decoded -> decoded.any { it == null } }
            ?.filterNotNull()
    }

    private fun pending(key: String, value: JsonElement, bound: Set<String>): Boolean {
        val body = value as? JsonObject ?: return false
        if (key in bound || JsonScalars.str(body, HubFields.SERVER).isNullOrEmpty()) return false
        val absent = listOf(HubFields.SOCKET, HubFields.GENERATION).all { field ->
            body[field] == null || body[field] == JsonNull
        }
        return absent
    }

    private fun route(value: JsonElement): HubRoute? = (value as? JsonObject)?.let { body ->
        val server = JsonScalars.str(body, HubFields.SERVER)?.takeIf(String::isNotEmpty)
        val socket = JsonScalars.str(body, HubFields.SOCKET)?.takeIf(String::isNotEmpty)
        if (server != null && socket != null) {
            HubRoute(
                server,
                JsonScalars.str(body, HubFields.LAUNCH).orEmpty(),
                socket,
                JsonScalars.str(body, HubFields.SHARED) != "false",
                JsonScalars.str(body, HubFields.GENERATION),
            )
        } else {
            null
        }
    }
}
