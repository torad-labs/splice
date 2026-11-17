// NEW: door-default (docs/specs/door-default.md, decision 3). Claude Desktop's Code tab as a caller of the door
// (docs/specs/desktop-door.md): its desktop key, its switch (`splice desktop on` and `off`), its default model and
// window from the door's record, and the last turn it sent, kept for `splice desktop`.
package splice.app.desktop

import splice.client.desktop.DESKTOP_ENV_PLACE
import splice.client.desktop.DesktopEnvironment
import splice.core.turn.DESKTOP_DOOR
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.share.RefusalVoice
import splice.share.ShareRelay

internal class DesktopCaller(
    relay: ShareRelay,
    private val key: DesktopKey,
    private val records: DesktopRecords,
    private val log: LogSink,
) : DoorCaller(RefusalVoice(DESKTOP_ERROR_PREFIX), relay, DESKTOP_DOOR) {
    private val off = "the door is off. `splice desktop on` opens it; to put the Code tab back on your own Claude " +
        "login, remove ${DesktopEnvironment().names.joinToString()} in $DESKTOP_ENV_PLACE"

    override fun presents(header: String?): Boolean = key.matchesBearer(header)

    override fun closed(): String? = off.takeUnless { records.get().on }

    override fun default(lineup: DesktopLineup): String? = records.get().defaultModel

    override fun window(): Long? = records.get().window

    override val noHead: String = "no head is in the door; `splice desktop` says why"

    override val notInDoor: String = "is not in the door; `splice desktop` lists the models it serves"

    /** The last door turn, kept for `splice desktop`. A record the disk refuses is said in the log, and the turn goes
     *  on: what it is for is the answer, not the record of it. */
    override fun sending(turn: DesktopLastTurn) {
        Cancellables.runCatchingCancellable { records.update { it.copy(lastTurn = turn) } }.onFailure { failure ->
            log("[desktop] the last door turn was not recorded: ${SafeFailureText.render(failure)}\n")
        }
    }
}
