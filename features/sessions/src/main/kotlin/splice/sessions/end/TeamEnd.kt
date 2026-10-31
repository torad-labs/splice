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
// Unconfirmed, each member's own End checks again when its turn comes, so a member that started a turn or got unsent
// words while the ones before it closed is never ended unasked: End all stops there, the members already ended stay
// ended, and the answer is the same 409 as a first ask, listing that member and every member still running that
// would lose something.
//
//   200  {"members": [{"slot", "status", "reason"?}]}   404  no such team.
//   409  {"reason": "costs", "members": [{"slot", "reason", "draft"?}]}   503  no team store.
package splice.sessions.end

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonScalars
import splice.http.JsonReply
import splice.sessions.http.NO_SUCH_TEAM
import splice.sessions.http.Refusal
import splice.sessions.http.Refusals
import splice.sessions.http.TEAMS_UNWIRED
import splice.sessions.http.TeamSource

private const val COSTS = "costs"
private const val REASON = "reason"

public class TeamEnd(
    private val teams: TeamSource,
    private val end: SessionEnd,
) {
    /** The keys a member's own End refuses with when ending it would lose something, not when it cannot end it. */
    private val losses = setOf(Refusal.WORKING.key, Refusal.DRAFT.key)

    public suspend fun endJson(teamId: String, body: String): JsonReply =
        endAll(teamId, JsonScalars.objectOrNull(Json, body)?.let { JsonScalars.str(it, "confirm") } == "true")

    public suspend fun endAll(teamId: String, confirm: Boolean): JsonReply {
        val store = teams() ?: return Refusals.reply(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        val team = store.team(teamId) ?: return Refusals.reply(HttpStatusCode.NotFound, "$NO_SUCH_TEAM$teamId")
        val members = team.slots.mapNotNull { slot -> slot.session?.let { slot.id to it } }
        val costs = if (confirm) emptyList() else costs(members)
        return if (costs.isNotEmpty()) reply(HttpStatusCode.Conflict, COSTS, costs) else endEach(members, confirm)
    }

    /** Each of [members] that would lose something if ended now, with the refusal that names what. */
    private fun costs(members: List<Pair<String, String>>): List<Triple<String, String, JsonReply>> =
        members.mapNotNull { (slot, s) -> end.cost(s)?.let { Triple(slot, s, it) } }

    /** Each member ended in turn by its own End, carrying his confirm, so a loss that came up since the first look
     *  stops End all at that member rather than going unasked. */
    private suspend fun endEach(members: List<Pair<String, String>>, confirm: Boolean): JsonReply {
        val each = mutableListOf<Triple<String, String, JsonReply>>()
        for ((i, member) in members.withIndex()) {
            val (slot, s) = member
            val answer = end.end(s, confirm)
            if (!confirm && lost(answer)) {
                val still = listOf(Triple(slot, s, answer)) + costs(members.drop(i + 1))
                return reply(HttpStatusCode.Conflict, COSTS, still)
            }
            each += Triple(slot, s, answer)
        }
        return reply(HttpStatusCode.OK, null, each)
    }

    /** Whether a member's End refused because ending it would lose something. */
    private fun lost(answer: JsonReply): Boolean = answer.status == HttpStatusCode.Conflict &&
        JsonScalars.str(JsonScalars.objectOrNull(Json, answer.body), REASON) in losses

    /** Each member's slot, session and the reply its own End gave, as one answer. */
    private fun reply(status: HttpStatusCode, reason: String?, each: List<Triple<String, String, JsonReply>>) =
        JsonReply(
            status,
            buildJsonObject {
                reason?.let { put(REASON, it) }
                putJsonArray("members") {
                    each.forEach { (slot, _, answer) ->
                        // the member's own End answered in our JSON; its reason and any unsent words go on
                        val said = JsonScalars.objectOrNull(Json, answer.body)
                        add(
                            buildJsonObject {
                                put("slot", slot)
                                put("status", answer.status.value)
                                said?.get(REASON)?.let { put(REASON, it) }
                                said?.get("draft")?.let { put("draft", it) }
                            },
                        )
                    }
                }
            }.toString(),
        )
}
