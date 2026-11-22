// NEW: server mode (docs/specs/server-mode.md, F14): `splice server backup --now`, triggers an immediate state
// snapshot to `[server.backup]`.
package splice.app.cli.server

import kotlinx.serialization.json.JsonObject
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.JSON_SWITCH
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerAsk
import splice.core.util.JsonScalars

internal class ServerBackupVerbs(private val ask: PeerAsk) {
    /** `backup --now [--json]` */
    fun now(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf(BACKUP_NOW_FLAG, JSON_SWITCH)).parse(args)
            ?.takeIf { it.words.isEmpty() && BACKUP_NOW_FLAG in it.switches } ?: return null
        return ask.managed(ControlRequest("POST", BACKUP_API_PATH), JSON_SWITCH in parsed.switches) { render(it) }
    }

    private fun render(body: JsonObject): String {
        val to = JsonScalars.str(body, "to").orEmpty()
        val key = JsonScalars.str(body, "key").orEmpty()
        val files = JsonScalars.long(body, "files") ?: 0L
        return "backed up $files state file(s) to $to ($key)"
    }
}

private const val BACKUP_API_PATH = "/api/server/backup"
private const val BACKUP_NOW_FLAG = "--now"
