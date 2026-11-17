// NEW: server mode (docs/specs/server-mode.md, F12): `splice server team set <team> --commands a,b`, the commands a
// team's people may use, from each one's next turn, unless their own `person set --commands` says otherwise. Whether
// each is a command the server serves is the daemon's to say, and an empty list is refused there too, by name.
package splice.app.cli.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerAsk
import splice.server.COMMANDS_FIELD

internal class ServerTeamVerbs(private val ask: PeerAsk) {
    private val lines = ServerLines()

    /** `team set <team> --commands a,b`. */
    fun set(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(COMMANDS_FLAG), emptySet()).parse(args)
        val team = parsed?.words?.singleOrNull()
        val commands = parsed?.values?.get(COMMANDS_FLAG)
        if (team == null || commands == null) return null
        val body = buildJsonObject { put(COMMANDS_FIELD, CommandsFlag().list(commands)) }
        return ask.managed(ControlRequest("PUT", "$TEAMS_PATH/${ask.segment(team)}", body)) { lines.team(it) }
    }
}

/** How `--commands` is sent: `all`, for a person, as itself, and anything else as the list its commas part. */
internal class CommandsFlag {
    fun list(text: String): JsonElement = JsonArray(text.split(',').map(String::trim).map(::JsonPrimitive))

    fun personal(text: String): JsonElement = if (text.trim() == ALL) JsonPrimitive(ALL) else list(text)
}

internal const val COMMANDS_FLAG = "--commands"
private const val ALL = "all"
private const val TEAMS_PATH = "/api/server/teams"
