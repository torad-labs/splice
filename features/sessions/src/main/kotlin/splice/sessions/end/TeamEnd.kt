// NEW: Oct 12, 2026 — "End all" on a team: every started member's session ended, the team and its slots kept (fin,
// Marlin's p218 ruling).
//
//   POST /api/teams/{id}/end   {"confirm": true}
//
// Asked without "confirm", nothing ends while any member would lose something (a turn mid-work, words typed and not
// sent): the answer lists those members and what each would lose, so the page arms with a chip for each. With nothing
// to lose, or confirmed, each started member is ended as its own End would (SessionEnd), and the answer gives each
// member's outcome by slot; a member whose session had already ended reads as ended.
//
//   200  {"members": [{"slot", "session_id", "status", "reason"?}]}   404  no such team.
//   409  {"reason": "costs", "members": [{"slot", "session_id", "reason", "draft"?}]}   503  no team store.
package splice.sessions.end

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonScalars
import splice.http.JsonReply
import splice.sessions.http.NO_SUCH_TEAM
import splice.sessions.http.Refusals
import splice.sessions.http.TEAMS_UNWIRED
import splice.sessions.http.TeamSource

public class TeamEnd(
    private val teams: TeamSource,
    private val end: SessionEnd,
) {
    public suspend fun endJson(teamId: String, body: String): JsonReply =
        endAll(teamId, JsonScalars.objectOrNull(Json, body)?.let { JsonScalars.str(it, "confirm") } == "true")

    public suspend fun endAll(teamId: String, confirm: Boolean): JsonReply {
        val store = teams() ?: return Refusals.reply(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        val team = store.team(teamId) ?: return Refusals.reply(HttpStatusCode.NotFound, "$NO_SUCH_TEAM$teamId")
        val members = team.slots.mapNotNull { slot -> slot.session?.let { slot.id to it } }
        val costs = if (confirm) {
            emptyList()
        } else {
            members.mapNotNull { (slot, s) -> end.cost(s)?.let { Triple(slot, s, it) } }
        }
        return if (costs.isNotEmpty()) {
            reply(HttpStatusCode.Conflict, "costs", costs)
        } else {
            reply(HttpStatusCode.OK, null, members.map { (slot, s) -> Triple(slot, s, end.end(s, confirm = true)) })
        }
    }

    /** Each member's slot, session and the reply its own End gave, as one answer. */
    private fun reply(status: HttpStatusCode, reason: String?, each: List<Triple<String, String, JsonReply>>) =
        JsonReply(
            status,
            buildJsonObject {
                reason?.let { put("reason", it) }
                putJsonArray("members") {
                    each.forEach { (slot, session, answer) ->
                        // the member's own End answered in our JSON; its reason and any unsent words go on
                        val said = JsonScalars.objectOrNull(Json, answer.body)
                        add(
                            buildJsonObject {
                                put("slot", slot)
                                put("session_id", session)
                                put("status", answer.status.value)
                                said?.get("reason")?.let { put("reason", it) }
                                said?.get("draft")?.let { put("draft", it) }
                            },
                        )
                    }
                }
            }.toString(),
        )
}
