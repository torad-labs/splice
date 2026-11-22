// NEW: server mode (docs/specs/server-mode.md, F12). The server's operation on one team's settings, as `splice server
// team set <team> --commands a,b` asks it through the control plane: the commands the team's people may use, each one
// a command this server serves. It holds from each of its people's next turn, for everyone on the team whose own
// setting does not say otherwise.
package splice.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.server.audit.CONTROL_ACTOR
import splice.server.audit.ServerAudit

public class TeamApi(
    private val people: ServerPeople,
    private val log: LogSink,
    /** `[server] commands`, the commands a team may be let use. */
    served: List<String>,
    private val audit: ServerAudit? = null,
) {
    private val commandNames = CommandNames(served)

    /** Sets `{"commands": [names]}` for [team] and answers `{"team", "commands"}` as they now stand. */
    public suspend fun set(team: String, body: JsonObject?, actor: String = CONTROL_ACTOR): ServerAnswer {
        val name = team.trim().takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }
        val read = body?.get(COMMANDS_FIELD)?.let { commandNames.read(it, everyAllowed = false) }
        val grant = (read as? GrantReading.Granted)?.grant
        if (name == null || grant == null) {
            val why = (read as? GrantReading.Refused)?.why ?: "name the team's commands, as in [\"claude-x\"]"
            return refused(if (name == null) "the team must be a name, as in core" else why)
        }
        val now = people.setTeam(name, TeamSettings(commands = grant.names))
        log("[server] settings of team ${LogSafe.str(name)} changed\n")
        val reply = buildJsonObject {
            put(TEAM, name)
            putJsonArray(COMMANDS_FIELD) { now.commands.orEmpty().forEach { add(it) } }
        }
        audit?.record(actor, "team.set", name, reply)
        return ServerAnswer.Done(HttpStatusCode.OK, reply)
    }

    private fun refused(why: String): ServerAnswer = ServerAnswer.Refused(HttpStatusCode.BadRequest, why)
}

// The field every team answer names its team in.
private const val TEAM = "team"
