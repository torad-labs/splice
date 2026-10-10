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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.session.SessionKey
import splice.core.util.Cancellables
import splice.http.JsonReply
import splice.sessions.prompt.ScreenChoices
import splice.sessions.prompt.ScreenOffer

/** Where the numbered keys begin, so a key answers with the digit the person presses. */
private val FIRST_CHOICE = SessionKey.CHOICE_1.ordinal

private const val NO_TERMINAL_TO_READ = "splice has no terminal to read this session's screen in"
private const val NOT_OPENED_BY_SPLICE =
    "splice did not start this session, so it cannot read its screen; look at the terminal it runs in"

/** The live prompt on a member's screen, for the card that draws its choices. */
public class TeamScreen(
    private val teams: TeamSource,
    private val driver: TerminalSource,
    private val choices: ScreenChoices = ScreenChoices(),
) {
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

    private fun read(session: String?): JsonReply {
        val driving = driver()
        val pane = session?.let { driving?.panes?.paneFor(it) }
        return when {
            session == null -> refuse(HttpStatusCode.Conflict, "that slot has no session")
            driving == null -> refuse(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_READ)
            pane == null -> refuse(HttpStatusCode.Conflict, NOT_OPENED_BY_SPLICE)
            // A terminal that will not answer is a screen splice could not read, which the card already draws
            // for: it offers nothing and keeps its fallback, rather than failing a card over one unreadable pane.
            else -> Cancellables.runCatchingCleanup { driving.terminal.screen(pane) }.fold(
                onSuccess = { spelled(session, choices.on(it)) },
                onFailure = { spelled(session, ScreenOffer("", emptyList())) },
            )
        }
    }

    private fun spelled(session: String, offer: ScreenOffer): JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            put("session_id", session)
            put("asked", offer.asked)
            putJsonArray("choices") {
                offer.choices.forEach { choice ->
                    add(
                        buildJsonObject {
                            // The digit the person presses, which is the same number POST .../answer takes.
                            put("choice", choice.key.ordinal - FIRST_CHOICE + 1)
                            put("label", choice.label)
                            put("here", choice.here)
                        },
                    )
                }
            }
        }.toString(),
    )

    private fun refuse(status: HttpStatusCode, sentence: String) =
        JsonReply(status, buildJsonObject { put("error", sentence) }.toString())
}
