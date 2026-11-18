// NEW: server mode (docs/specs/server-mode.md, F7). `splice remote refresh <server>`: the laptop follows a team's
// server as it adds and drops commands. The server's profile is asked with the key this laptop holds there; a command
// it lists that it did not list at the last add or refresh is linked, at `<server>/<command>`, under the same rules as
// `add`; a command linked from that server that the profile no longer lists is unlinked. The laptop's own commands, and
// those it runs on other machines, are left alone, and a command the person took out of a server's list stays out
// while the server keeps offering it. The telemetry relay the profile names is kept too (F8), and the next launch of
// each of the server's commands sends its telemetry there, or none once the server names none. A profile that lists
// what it listed before, and the same relay, changes nothing.
package splice.app.cli.share

import splice.app.cli.server.EXAMPLE_SERVER
import splice.core.terminal.TerminalOutput
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.core.topology.Topology

/** What a refresh wrote: the file as it now is, the commands it [linked] and [unlinked], and whether every command the
 *  server started offering was linked ([complete]); one that was not is said already. */
internal data class RemoteRefreshed(
    val topology: Topology,
    val linked: List<String>,
    val unlinked: List<String>,
    val complete: Boolean,
)

/** Takes a command's link out, while splice.toml still names the command. */
internal fun interface Unlinks {
    operator fun invoke(command: String)
}

internal class RemoteRefresh(
    private val out: TerminalOutput,
    private val file: ShareTopologyFile,
    private val asks: RemoteAsks,
    private val names: RemoteNames,
) {
    /** [text], a server, followed; null, said, when it could not be. */
    suspend fun refresh(text: String, unlink: Unlinks): RemoteRefreshed? {
        val server = names.serverOnly(names.address(text))
            ?: return names.refused(
                "$text names more than a server; refresh takes the server alone, as in $EXAMPLE_SERVER",
            )
        val topology = file.loaded() ?: return null
        return offered(server)?.let { followed(server, topology, it, unlink) }
    }

    /** What [server]'s profile offers, asked with the key this laptop holds there; null, said, when it holds none or
     *  the server refused it. */
    private suspend fun offered(server: String): ServerOffer? {
        val held = asks.profiles.held(server)
            ?: return names.refused("this laptop holds no key for $server; keep one first: splice login $server")
        return names.profiled(server, held)
    }

    /** [topology] following [server], whose profile makes [offer] now; each command it drops loses its link through
     *  [unlink] first. */
    private fun followed(server: String, topology: Topology, offer: ServerOffer, unlink: Unlinks): RemoteRefreshed? {
        val offered = offer.commands
        val linked = linkedFrom(topology, server)
        val before = asks.logins.offered(server) ?: linked.values.distinct()
        val unlinked = linked.filterValues { it !in offered }.keys
        val adding = names.free(topology, offer.entries.filterKeys { it !in before && it !in linked.values })
        val wanted = topology.copy(remote = topology.remote - unlinked + adding.entries)
        val unchanged = wanted == topology
        val relayed = asks.logins.telemetry(server) != offer.telemetry
        if (unchanged && !relayed) {
            out.line("nothing changed: $server offers ${offered.joinToString(", ").ifEmpty { "nothing" }}, as before")
        }
        unlinked.forEach { unlink(it) }
        if (!unchanged && !file.saved(wanted, topology)) return null
        asks.logins.keepOffered(server, offered, offer.telemetry)
        if (relayed) relayMoved(server, offer.telemetry)
        return RemoteRefreshed(wanted, adding.entries.keys.toList(), unlinked.toList(), adding.complete)
    }

    /** Says that [server]'s commands now send Claude Code's telemetry to [relay], or to no relay when it is null. */
    private fun relayMoved(server: String, relay: String?) {
        val now = relay?.let { "goes to $it" } ?: "is no longer relayed"
        out.line("Claude Code's telemetry from $server's commands $now, from their next launch")
    }

    /** The commands [topology] runs on [server], each with the command it is there. */
    private fun linkedFrom(topology: Topology, server: String): Map<String, String> {
        val origin = asks.logins.origin(server)
        return topology.remote.mapNotNull { (command, remote) ->
            val address = (RemoteAddresses.read(remote.url) as? RemoteAddressRead.Read)?.address
            address?.takeIf { asks.logins.origin(it.server) == origin }?.let { command to it.head }
        }.toMap()
    }
}
