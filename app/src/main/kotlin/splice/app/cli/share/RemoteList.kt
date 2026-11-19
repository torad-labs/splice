// The door on the network (docs/specs/door-network.md). The bare `splice remote`: each command this machine runs on
// another machine, its address, a context window pinned on this laptop (docs/specs/server-mode.md, F7), and whether it
// runs there now. A door entry is asked of its door, once per machine, and says how many models it serves. An entry
// naming one command is read from the server's `GET /` as before: on a team server with this laptop's key, and, for an
// entry left from the per-command days on a machine this laptop holds no key for, to say what replaces it, since a
// machine shares its door alone since share-door-only (Nov 19, 2026) and lists no command of its own.
package splice.app.cli.share

import splice.app.cli.peer.PeerExit
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.share.Asked
import splice.share.DOOR_SEGMENT
import splice.share.ShareInfo
import splice.share.SharedDoorLaunch

internal class RemoteList(
    private val out: TerminalOutput,
    private val file: ShareTopologyFile,
    private val asks: RemoteAsks,
) {
    private val doors = mutableMapOf<String, Asked<SharedDoorLaunch>>()
    private val directories = mutableMapOf<String, Asked<ShareInfo>>()

    suspend fun list(): Int {
        val topology = file.loaded() ?: return PeerExit.REFUSED
        if (topology.remote.isEmpty()) {
            out.line("no command here runs on another machine.")
            out.line("  on the machine with the accounts: ${CYAN}splice share on$RESET, which prints its address")
            out.line("  then here: ${CYAN}splice remote add http://<that machine>$RESET")
            return PeerExit.DONE
        }
        topology.remote.forEach { (command, remote) ->
            val state = when (val read = RemoteAddresses.read(remote.url)) {
                is RemoteAddressRead.Refused -> "not an address it can run on: ${read.why}"
                is RemoteAddressRead.Read -> state(read.address)
            }
            val window = remote.window?.let { "  window $it, pinned here" }.orEmpty()
            out.line("  ${command.padEnd(COMMAND_PAD)} ${remote.url}$window  $DIM$state$RESET")
        }
        return PeerExit.DONE
    }

    /** Whether [address] runs on its server now: a door as its door says, one command as the server's offer says. */
    private suspend fun state(address: RemoteAddress): String {
        val server = address.server
        if (address.head == DOOR_SEGMENT) return doorState(doors.getOrPut(server) { asks.directory.door(server) })
        return when (val read = directories.getOrPut(server) { asks.directory.read(server) }) {
            is Asked.Refused -> read.why
            is Asked.Had -> offerState(address, read.value)
        }
    }

    /** Whether [address]'s one command runs on its server now, as the server's offer [info] says; a machine that no
     *  longer lists it, on a laptop holding no key there, is told what replaces it. */
    private fun offerState(address: RemoteAddress, info: ShareInfo): String {
        val server = address.server
        val offer = info.commands.firstOrNull { it.command == address.head }
            ?: info.commands.firstOrNull { it.head == address.head }
        return when {
            offer == null && asks.logins.of(server) == null ->
                "${info.machine} no longer shares one command at an address of its own; add its door with: " +
                    "splice remote add $server"
            offer == null -> "${info.machine} no longer offers ${address.head}"
            !offer.running -> "not running on ${info.machine}"
            else -> "ready on ${info.machine}"
        }
    }

    /** A door entry's state, as its door's launch [read] says: ready, with the models it serves, or why not. */
    private fun doorState(read: Asked<SharedDoorLaunch>): String = when (read) {
        is Asked.Refused -> read.why
        is Asked.Had -> "ready on ${read.value.machine}'s door, ${read.value.served}"
    }
}
