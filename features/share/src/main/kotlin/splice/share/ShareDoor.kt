// NEW: door-network (docs/specs/door-network.md). The door, answered on the share listener as well as on loopback, so
// a machine on this one's LAN or tailnet runs plain `claude` on every model this machine serves, the way Claude Code
// runs on Bedrock: Claude Code's Mantle dialect at `<machine>:<port>/door`, where the model a request names picks the
// provider, on the accounts signed in here. The other machine holds no credential: it is admitted by where it is
// (LanAdmission), as every share request is, and its bearer is the fixed word every node sends.
//
//   GET  /door/launch                     what the other machine's `claude` launches with (SharedDoorLaunch)
//   POST /door/v1/messages[?beta=true]    a turn, run by the door as it runs the terminal's
//   POST /door/v1/messages/count_tokens   a count, the same way
//
// The daemon that runs the door implements this; the share module only routes to it.
package splice.share

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

/** The door, as the share listener reaches it. */
public interface ShareDoor {
    /** What a launch of `claude` on another machine needs now, or null while the door is not open yet (the heads are
     *  still starting). Read at each launch, so a sign-in here reaches the next session there with no restart. */
    public suspend fun launch(): SharedDoorLaunch?

    /** A turn ([counting] false) or a count from another machine, answered as the door answers the terminal's. */
    public suspend fun answer(call: ApplicationCall, counting: Boolean)
}

/** What a launch of `claude` on another machine is built from: the [machine] that serves it, the [window] its
 *  sessions declare, the selector a session starts on ([model], null with no account signed in) and the picker's
 *  [rows], in splice.toml's order of heads. */
@Serializable
public data class SharedDoorLaunch(
    val machine: String,
    val window: Long,
    val model: String? = null,
    val rows: List<SharedDoorRow> = emptyList(),
    /** #791: the client timeout a session there must outlive, the longest wall of that machine's providers; absent
     *  from a machine before it, and null while it has none. */
    val wallMs: Long? = null,
)

/** One row of the picker: the selector a pick sends, the model's label, and the command its head runs as there. */
@Serializable
public data class SharedDoorRow(
    val model: String,
    val label: String,
    val command: String,
)

/** What a door whose heads are still starting says it is waiting for, after the machine's name: `splice remote add`
 *  tells it from a machine that has no door. */
public const val DOOR_STARTING: String = "opens once its heads are up"
