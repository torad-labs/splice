// NEW: server mode (docs/specs/server-mode.md, F16): `splice server audit [--tail N] [--json]`, prints the
// newest administrative actions recorded by the server.
package splice.app.cli.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.JSON_SWITCH
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerAsk
import splice.core.util.JsonScalars

internal class ServerAuditVerbs(private val ask: PeerAsk) {
    /** `audit [--tail N] [--json]` */
    fun list(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(TAIL_FLAG), setOf(JSON_SWITCH)).parse(args)
            ?.takeIf { it.words.isEmpty() } ?: return null
        val tail = parsed.values[TAIL_FLAG]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it > 0 } ?: return null
        }
        val path = if (tail == null) AUDIT_PATH else "$AUDIT_PATH?tail=$tail"
        return ask.managed(ControlRequest("GET", path), JSON_SWITCH in parsed.switches) { render(it) }
    }

    private fun render(body: JsonObject): String {
        val entries = (body["entries"] as? JsonArray).orEmpty().map { it.jsonObject }
        if (entries.isEmpty()) return "no audit entries recorded yet"
        return entries.joinToString("\n") { row ->
            val ts = JsonScalars.str(row, "ts").orEmpty()
            val actor = JsonScalars.str(row, "actor").orEmpty()
            val action = JsonScalars.str(row, "action").orEmpty()
            val target = JsonScalars.str(row, "target").orEmpty()
            "$ts  $actor  $action  $target"
        }
    }
}

private const val AUDIT_PATH = "/api/server/audit"
private const val TAIL_FLAG = "--tail"
