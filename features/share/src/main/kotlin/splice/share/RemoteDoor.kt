// NEW: door-network (docs/specs/door-network.md). The launch of `claude` on another machine's door
// (`[remote.claude] url = "http://<machine>:3097/door"`): asked of that machine at each launch, so a sign-in there
// reaches the next session here with no restart, and built as `claude` on the door here is (DoorSession), with that
// machine's door as the Mantle base URL and the fixed word every node sends as its bearer. No credential of that
// machine's is held here: it signs every turn with its own accounts. The session runs over the person's own Claude
// folder. A machine that can't be reached, or refuses, is said before Claude Code starts, naming it and the reason.
package splice.share

import splice.client.desktop.DoorPickerRow
import splice.client.desktop.DoorSession
import splice.core.topology.RemoteAddress
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.wire.HttpStatus
import splice.launch.RemoteLaunch
import splice.launch.recipe.DoorLaunchSpec
import java.net.URI

/** Whether a launch here carries splice's launch mark in its overlay, and under which key (LaunchMarker): the daemon
 *  says, since the share module does not read the process table. */
public data class DoorMark(val here: Boolean = false, val key: String = "")

internal class RemoteDoor(private val http: ShareHttp, private val mark: DoorMark) {
    /** [command]'s launch on the door [address] names, on the [window] this machine pinned for it if it did. */
    suspend fun launch(command: String, address: RemoteAddress, window: Long?): RemoteLaunch {
        val where = "$command runs on ${http.hostOf(address.server)}'s door (${address.server})"
        val answer = when (val asked = http.get(URI.create("${address.server}/$DOOR_SEGMENT/$HEAD_LAUNCH_ROUTE"))) {
            is ShareAnswer.Unreachable -> return RemoteLaunch.Refused("$where, which can't be reached: ${asked.why}")
            is ShareAnswer.Answered -> asked
        }
        val shared = answer.takeIf { it.status == HttpStatus.OK }
            ?.let { http.decoded(it.body, SharedDoorLaunch.serializer()) }
        return when {
            shared != null -> RemoteLaunch.Door(spec(address, shared, window))
            answer.status != HttpStatus.OK ->
                RemoteLaunch.Refused("$where, which refused it: ${http.said(answer.body)}")
            else -> RemoteLaunch.Refused("$where, which answered with something that is not a door's launch")
        }
    }

    /** What `claude` here launches with on the door [shared] describes: that machine's models as the picker's rows,
     *  its window unless this machine pinned one, and its door as the base URL. */
    private fun spec(address: RemoteAddress, shared: SharedDoorLaunch, window: Long?): DoorLaunchSpec {
        val rows = shared.rows.map { DoorPickerRow(it.model, it.label, "${it.command} on ${shared.machine}") }
        val door = "${address.server}/$DOOR_SEGMENT"
        val session = DoorSession(door, SHARE_NODE_TOKEN, window ?: shared.window, shared.model, rows)
        return DoorLaunchSpec(session.env(), session.unset(), session.overlay(mark.here, mark.key))
    }
}
