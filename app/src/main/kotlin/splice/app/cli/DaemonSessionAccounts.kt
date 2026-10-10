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
import splice.sessions.list.SessionAccountKey
import splice.sessions.list.SessionAccountKeys
import splice.sessions.list.SessionAccountLine
import splice.sessions.list.SessionAccounts

internal class DaemonSessionAccounts : SessionAccounts {
    override fun read(envReader: EnvReader): Map<SessionAccountKey, SessionAccountLine> {
        val key = (MgmtKeyFile().read(envReader) as? MgmtKeyRead.Present)?.key ?: return emptyMap()
        val port = AdminSupport.controlPort(envReader)
        val reply = ControlPlaneClient.send("http://127.0.0.1:$port/api/sessions", "GET", key)
            ?.takeIf { it.status in ControlPlaneClient.OK_RANGE } ?: return emptyMap()
        return lines(reply.body)
    }

    /** The accounts of [body], one per head and session. A head can hold two registrations of one session id (a
     *  live process and the stale file it left); the live one speaks, so a gone registration with empty fields
     *  never overwrites it (review of adf35c39e, finding 2). */
    internal fun lines(body: String): Map<SessionAccountKey, SessionAccountLine> =
        rows(body).mapNotNull { row ->
            val id = text(row, "session_id") ?: return@mapNotNull null
            val line = SessionAccountLine(text(row, "account"), text(row, "account_pin"))
            SessionAccountKeys.of(text(row, "head"), id) to (rank(row) to line)
        }.groupBy({ it.first }, { it.second }).mapValues { (_, candidates) ->
            candidates.maxWith(compareBy({ it.first }, { it.second.account != null || it.second.pin != null })).second
        }

    private fun rows(body: String): List<JsonObject> {
        val sessions = try {
            (Json.parseToJsonElement(body) as? JsonObject)?.get("sessions")
        } catch (_: SerializationException) {
            null
        }
        return (sessions as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    }

    /** live above stale above gone: the order in which a registration still speaks for its session. */
    private fun rank(row: JsonObject): Int = when (text(row, "availability")) {
        "live" -> 2
        "stale" -> 1
        else -> 0
    }

    private fun text(row: JsonObject, field: String): String? =
        JsonScalars.str(row, field)?.takeIf(String::isNotBlank)
}
