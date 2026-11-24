// NEW: door-network (docs/specs/door-network.md). The door as another machine reaches it through the share listener:
// `claude` there runs on every model this machine serves, on the accounts signed in here, as the terminal's `claude`
// does here. That machine holds no key: the share listener admits it by where it is (LanAdmission), so this caller is
// never told by a key, and the control port's door routes, which admit by key alone, never admit it. A call at the
// share listener that does present Desktop's key or the terminal's is answered as that caller (DesktopDoor.answerAs):
// the door is one, at either listener. Its sessions start on the model and declare the window the terminal's lane
// says, and a model of Anthropic's that a request names runs where a provider here serves it, as for the terminal. A
// turn's row reads as the terminal's: it is a `claude` session of this machine's door, wherever it was typed.
package splice.app.desktop

import io.ktor.server.application.ApplicationCall
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.lane.ClaudeLane
import splice.client.desktop.DoorPickerRow
import splice.core.turn.TERMINAL_DOOR
import splice.core.wire.HttpStatus
import splice.share.DOOR_STARTING
import splice.share.RefusalVoice
import splice.share.ShareDoor
import splice.share.SharedDoorLaunch
import splice.share.SharedDoorRow

internal class NetworkCaller(private val lane: ClaudeLane, machine: String) :
    DoorCaller(RefusalVoice(NETWORK_ERROR_PREFIX), TERMINAL_DOOR) {
    override fun presents(header: String?): Boolean = false

    override fun closed(): String? = null

    override fun default(lineup: DesktopLineup): String? = lane.starting(lineup).first

    override fun window(): Long? = lane.declared()

    override val noHead: String =
        "no account is signed in on $machine, so no model is in its door; sign one in there with `splice console`"

    override val notInDoor: String = "is not in $machine's door; /model lists the models it serves"

    override val runsNamedModels: Boolean = true
}

/** The door the share listener answers other machines with; open once the daemon's door is. */
internal class NetworkDoor : ShareDoor {
    private val voice = RefusalVoice(NETWORK_ERROR_PREFIX)

    @Volatile
    private var opened: Opened? = null

    /** What the door answers another machine with: the [door], as [caller], over the terminal's [lane], whose picker
     *  [rows] are the terminal's, on [machine]. */
    fun open(door: DesktopDoor, caller: NetworkCaller, lane: ClaudeLane, rows: PickerRows, machine: String) {
        opened = Opened(door, caller, lane, rows, machine)
    }

    override suspend fun launch(): SharedDoorLaunch? {
        val open = opened ?: return null
        val lineup = open.lane.lineup()
        val rows = open.rows(lineup).map { SharedDoorRow(it.model, it.label, it.command) }
        val starting = open.lane.starting(lineup).first
        return SharedDoorLaunch(open.machine, open.lane.window(lineup), starting, rows, lineup.clientWallMs())
    }

    override suspend fun answer(call: ApplicationCall, counting: Boolean) {
        val starting = "this machine's door $DOOR_STARTING"
        val open = opened ?: return voice.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, starting)
        open.door.answerAs(call, open.caller, counting)
    }

    private data class Opened(
        val door: DesktopDoor,
        val caller: NetworkCaller,
        val lane: ClaudeLane,
        val rows: PickerRows,
        val machine: String,
    )
}

/** The picker's rows of [DesktopLineup], as the terminal's `claude` lists them. */
internal fun interface PickerRows {
    operator fun invoke(lineup: DesktopLineup): List<DoorPickerRow>
}

/** What every refusal the door answers another machine with starts with. */
internal const val NETWORK_ERROR_PREFIX = "splice door: "
