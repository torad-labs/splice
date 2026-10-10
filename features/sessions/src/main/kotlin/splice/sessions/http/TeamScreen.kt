// NEW: Oct 10, 2026 — what a team member's session is showing right now, as choices a card can draw.
//
//   GET /api/teams/{id}/slots/{slot}/screen   the live prompt's question and its numbered choices
//
// WHY THE CARD NEEDS THIS AND NOT A LIST. A permission's choices belong to the Claude Code version the person
// is running (ScreenChoices, and SessionTerminal's own note on [screen]), so the card draws Allow / Always
// allow / Deny when the client drew them and nothing when it did not. An AskUserQuestion needs no screen at
// all: its question and labels come off the transcript, which /api/sessions already carries.
//
// NOTHING OFFERED IS AN ANSWER, NEVER AN EMPTY CARD. A screen splice cannot read a choice on answers 200 with
// an empty list, and the card keeps its fallback — what is being asked, and where it is answered. The same
// holds for a pane splice did not open: that is a refusal in words, not a cleared card.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.http.JsonReply
import splice.sessions.prompt.ScreenChoices

/** The live prompt on a member's screen, for the card that draws its choices. */
public class TeamScreen(
    private val teams: TeamSource,
    driver: TerminalSource,
    choices: ScreenChoices = ScreenChoices(),
) {
    private val drive = SessionDrive(driver, choices)

    public fun offer(teamId: String, slotId: String): JsonReply {
        val store = teams() ?: return refuse(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        val team = store.team(teamId) ?: return refuse(HttpStatusCode.NotFound, "$NO_SUCH_TEAM$teamId")
        val slot = team.slots.firstOrNull { it.id == slotId }
        return if (slot == null) {
            refuse(HttpStatusCode.NotFound, "no such slot in $teamId: $slotId")
        } else {
            read(slot.session)
        }
    }

    /** The member's session's screen, read the one way every session's is (SessionDrive.screen). */
    private fun read(session: String?): JsonReply =
        session?.let(drive::screen) ?: refuse(HttpStatusCode.Conflict, "that slot has no session")

    private fun refuse(status: HttpStatusCode, sentence: String) =
        JsonReply(status, buildJsonObject { put("error", sentence) }.toString())
}
