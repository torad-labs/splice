// NEW: door-default (docs/specs/door-default.md, decisions 3 and 6). The terminal's `claude` as a caller of the door:
// its own key, open while `claude` is on the door lane, the model a session starts on and the window it declares from
// the lane, and refusals in words that point at `splice claude` and, with nothing signed in, at `/login`.
package splice.app.desktop

import splice.core.turn.TERMINAL_DOOR
import splice.share.RefusalVoice
import splice.share.ShareRelay

internal class TerminalCaller(relay: ShareRelay, private val lane: ClaudeLane) :
    DoorCaller(RefusalVoice(CLAUDE_ERROR_PREFIX), relay, TERMINAL_DOOR) {
    override fun presents(header: String?): Boolean = lane.key.matchesBearer(header)

    override fun closed(): String? = ON_HEAD.takeUnless { lane.onDoor() }

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

// What a session of the door's is told once `claude` is back on its head.
private const val ON_HEAD = "claude is on its head now; `splice claude door` puts it back on the door"
