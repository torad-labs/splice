// NEW: v0.4.0 spec section 9 — `splice sessions` asks the running daemon which account each session is on and
// pinned to, since only the daemon holds the pools. A daemon that is down or will not answer gives an empty
// map, and the listing prints from the registry alone.
package splice.app.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import splice.sessions.list.SessionAccountLine
import splice.sessions.list.SessionAccounts

internal class DaemonSessionAccounts : SessionAccounts {
    override fun read(envReader: EnvReader): Map<String, SessionAccountLine> {
        val key = (MgmtKeyFile().read(envReader) as? MgmtKeyRead.Present)?.key ?: return emptyMap()
        val port = AdminSupport.controlPort(envReader)
        val reply = ControlPlaneClient.send("http://127.0.0.1:$port/api/sessions", "GET", key)
            ?.takeIf { it.status in ControlPlaneClient.OK_RANGE } ?: return emptyMap()
        val sessions = try {
            (Json.parseToJsonElement(reply.body) as? JsonObject)?.get("sessions")
        } catch (_: SerializationException) {
            return emptyMap()
        }
        val rows = (sessions as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return rows.mapNotNull { row ->
            val id = text(row, "session_id") ?: return@mapNotNull null
            id to SessionAccountLine(text(row, "account"), text(row, "account_pin"))
        }.toMap()
    }

    private fun text(row: JsonObject, field: String): String? =
        JsonScalars.str(row, field)?.takeIf(String::isNotBlank)
}
