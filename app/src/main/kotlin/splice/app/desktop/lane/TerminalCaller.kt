// NEW: door-default (docs/specs/door-default.md, decisions 3 and 6). The terminal's `claude` as a caller of the door:
// its own key, the model a session starts on and the window it declares from the lane, and refusals in words that point
// at `splice claude` and, with nothing signed in, at `/login`. The lane decides where a new session starts; the door
// stays open to a session already on it whichever lane `claude` is on.
package splice.app.desktop.lane

import splice.app.desktop.DoorCaller
import splice.app.desktop.catalog.DesktopLineup
import splice.core.turn.TERMINAL_DOOR
import splice.share.RefusalVoice

internal class TerminalCaller(private val lane: ClaudeLane) :
    DoorCaller(RefusalVoice(CLAUDE_ERROR_PREFIX), TERMINAL_DOOR) {
    override fun presents(header: String?): Boolean = lane.key.matchesBearer(header)

    // Never shut: a session started on the door keeps running there after `splice claude head`, which moves only the
    // sessions started after it. Until Nov 18, 2026 the door refused every turn of such a session once `claude` was
    // back on its head.
    override fun closed(): String? = null

    override fun default(lineup: DesktopLineup): String? = lane.starting(lineup).first

    override fun window(): Long? = lane.declared()

    override val noHead: String =
        "no account is signed in, so no model is in the door; type /login to sign in on splice's accounts page"

    override val notInDoor: String = "is not in the door; `splice claude` lists the models it serves"

    // A `claude --model claude-opus-5-5` session runs Opus, or is refused by name; never the lane's default model.
    override val runsNamedModels: Boolean = true
}

/** What every refusal the door answers the terminal's `claude` with starts with. */
internal const val CLAUDE_ERROR_PREFIX = "splice claude: "
