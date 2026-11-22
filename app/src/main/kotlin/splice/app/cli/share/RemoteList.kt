// LAN share (docs/specs/door-network.md). The bare `splice remote`: each command this machine runs on another
// machine, its address, a context window pinned on this laptop (docs/specs/server-mode.md, F7), and whether its server
// offers and runs it now. A door remote is said ready by asking the door, never `GET /`; an entry left from the
// per-command days is told the door's add that replaces it.
package splice.app.cli.share

import splice.app.cli.peer.PeerExit
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.DOOR_SEGMENT
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.share.Asked
import splice.share.ShareDirectory
import splice.share.ShareInfo
import splice.share.SharedDoorLaunch

internal class RemoteList(
    private val out: TerminalOutput,
    private val file: ShareTopologyFile,
    private val directory: ShareDirectory,
) {
    suspend fun list(): Int {
        val topology = file.loaded() ?: return PeerExit.REFUSED
        if (topology.remote.isEmpty()) {
            out.line("no command here runs on another machine.")
            out.line("  on the machine with the logins: ${CYAN}splice share on$RESET, which prints its door's add")
            out.line("  then here: ${CYAN}splice remote add <address>$RESET")
            return PeerExit.DONE
        }
        val doors = mutableMapOf<String, Asked<SharedDoorLaunch>>()
        val infos = mutableMapOf<String, Asked<ShareInfo>>()
        topology.remote.forEach { (command, remote) ->
            val state = when (val read = RemoteAddresses.read(remote.url)) {
                is RemoteAddressRead.Refused -> "not an address it can run on: ${read.why}"
                is RemoteAddressRead.Read -> {
                    val server = read.address.server
                    if (read.address.head == DOOR_SEGMENT) {
                        doorState(doors.getOrPut(server) { directory.door(server) })
                    } else {
                        leftoverState(read.address, infos.getOrPut(server) { directory.read(server) })
                    }
                }
            }
            val window = remote.window?.let { "  window $it, pinned here" }.orEmpty()
            out.line("  ${command.padEnd(COMMAND_PAD)} ${remote.url}$window  $DIM$state$RESET")
        }
        return PeerExit.DONE
    }

    /** Whether the door answers now, as its launch [read] says. */
    private fun doorState(read: Asked<SharedDoorLaunch>): String = when (read) {
        is Asked.Refused -> read.why
        is Asked.Had -> "ready on ${read.value.machine}'s door, ${read.value.rows.size} models"
    }

    /** What a command's own address [address] gets now: the machine no longer shares it on its own, and the add that
     *  puts this machine on the machine's door. */
    private fun leftoverState(address: RemoteAddress, read: Asked<ShareInfo>): String {
        val info = when (read) {
            is Asked.Refused -> return read.why
            is Asked.Had -> read.value
        }
        return "${info.machine} no longer shares ${address.head} on its own; on another machine, run: " +
            "splice remote add ${address.server}"
    }
}
