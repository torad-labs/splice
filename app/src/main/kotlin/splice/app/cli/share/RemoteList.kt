// LAN share (docs/specs/lan-share.md). The bare `splice remote`: each command this machine runs on another machine,
// its address, a context window pinned on this laptop (docs/specs/server-mode.md, F7), and whether its server offers
// and runs it now, read from each server once however many of its commands are listed.
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

internal class RemoteList(
    private val out: TerminalOutput,
    private val file: ShareTopologyFile,
    private val directory: ShareDirectory,
) {
    suspend fun list(): Int {
        val topology = file.loaded() ?: return PeerExit.REFUSED
        if (topology.remote.isEmpty()) {
            out.line("no command here runs on another machine.")
            out.line("  on the machine with the logins: ${CYAN}splice share on$RESET, which prints each address")
            out.line("  then here: ${CYAN}splice remote add <address>$RESET")
            return PeerExit.DONE
        }
        val servers = mutableMapOf<String, Asked<ShareInfo>>()
        topology.remote.forEach { (command, remote) ->
            val state = when (val read = RemoteAddresses.read(remote.url)) {
                is RemoteAddressRead.Refused -> "not an address it can run on: ${read.why}"
                is RemoteAddressRead.Read -> {
                    val server = read.address.server
                    state(read.address, servers.getOrPut(server) { directory.read(server) })
                }
            }
            val window = remote.window?.let { "  window $it, pinned here" }.orEmpty()
            out.line("  ${command.padEnd(COMMAND_PAD)} ${remote.url}$window  $DIM$state$RESET")
        }
        return PeerExit.DONE
    }

    /** Whether [address]'s command runs on its server now, as the server's offer [read] says. */
    private fun state(address: RemoteAddress, read: Asked<ShareInfo>): String {
        val info = when (read) {
            is Asked.Refused -> return read.why
            is Asked.Had -> read.value
        }
        // door-network: a machine that answers at all answers its door, which serves every model of every command.
        if (address.head == DOOR_SEGMENT) return "ready on ${info.machine}'s door"
        val offer = info.commands.firstOrNull { it.command == address.head }
            ?: info.commands.firstOrNull { it.head == address.head }
        return when {
            offer == null -> "${info.machine} no longer offers ${address.head}"
            !offer.running -> "not running on ${info.machine}"
            else -> "ready on ${info.machine}"
        }
    }
}
