// NEW: LAN share (docs/specs/lan-share.md). `splice remote add`: an address is checked against the server it names
// before anything is kept, so a typo is said now, never found out as a launch that fails or runs on the wrong login.
// The server must answer, offer the command, and have the login `auth=` names and every model `models=` lists. An
// address naming the server alone adds every command it offers. A name this machine already runs (a head here, or
// another address under that name) is refused with how to give the new one another: `--as <command>`. A relay the first
// LAN-share build wrote under that name is the one exception: the address replaces it, and its share token leaves
// keys.toml in the same add. `claude` is added only where `claude` is wrapped: an unwrapped `claude` is Claude Code
// itself, which never asks splice where to run, so the address would change nothing.
//
// A team's server (docs/specs/server-mode.md, F7) this laptop holds a key for is asked for its profile instead: every
// command the profile lists is linked, each at `<server>/<command>`, and the laptop keeps the list, so
// `splice remote refresh` later tells the commands the server starts offering from the ones the person took out. It
// keeps the telemetry relay the profile names as well (F8), which those commands' launches send their telemetry to.
package splice.app.cli.share

import splice.core.terminal.TerminalOutput
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology
import splice.share.Asked
import splice.share.ServerLogins
import splice.share.ServerProfile
import splice.share.ServerProfiles
import splice.share.ShareDirectory
import splice.share.ShareInfo
import splice.share.ShareOffer

/** What an add wrote: the file as it now is, the [commands] added, the [machine] they run on, and whether every one
 *  asked for was ([complete]); one that was not is said already. */
internal data class RemoteAdded(
    val topology: Topology,
    val commands: List<String>,
    val machine: String,
    val complete: Boolean,
)

/** The entries an address asks for, by command, and the [machine] that serves them; and the team [server] whose
 *  profile listed them, when one did, with the [telemetry] relay that profile names (F8). */
private data class Offered(
    val machine: String,
    val entries: Map<String, RemoteConfig>,
    val server: String? = null,
    val telemetry: String? = null,
)

/** Whether a command's link here runs splice's launch shim, so the command asks splice where to run: `claude` does
 *  once it is wrapped (InstallCommand.runsShim). */
internal fun interface ShimLinked {
    operator fun invoke(command: String): Boolean
}

/** What `splice remote` asks the other machine through: a LAN share's directory, a team server's profile, and the
 *  logins that hold this laptop's keys. */
internal data class RemoteAsks(
    val directory: ShareDirectory,
    val profiles: ServerProfiles,
    val logins: ServerLogins,
)

internal class RemoteAdd(
    private val out: TerminalOutput,
    private val file: ShareTopologyFile,
    private val asks: RemoteAsks,
    /** The first LAN-share build's relays, which an address under one's name replaces. */
    private val relays: FirstBuildRelays,
    private val names: RemoteNames,
) {
    /** [text] added, as [name] when given and as the server calls it otherwise; null, said, when nothing was. */
    suspend fun add(text: String, name: String?): RemoteAdded? {
        val topology = file.loadedOrStarter() ?: return null
        val address = names.address(text)
        val server = names.serverOnly(address)
        val offered = (if (server != null) every(server, name) else one(address, name)) ?: return null
        return kept(topology, offered)
    }

    /** [offered] written into [topology], each entry whose name is free here; one that is not is said. A first-build
     *  relay under an entry's name is replaced by it. */
    private fun kept(topology: Topology, offered: Offered): RemoteAdded? {
        val free = names.free(topology, offered.entries)
        // The same address added again keeps the window the person pinned on it.
        val fresh = free.entries.mapValues { (command, remote) ->
            topology.remote[command]?.takeIf { it.url == remote.url } ?: remote
        }
        val replacing = relays.replacing(topology, fresh.keys)
        val wanted = replacing.topology.let { it.copy(remote = it.remote + fresh) }
        if (fresh.isEmpty() || !file.saved(wanted, topology)) return null
        out.line("added to ${file.path}: ${fresh.keys.joinToString(", ")}")
        relays.replaced(topology, replacing)
        offered.server?.let { asks.logins.keepOffered(it, offered.entries.keys.toList(), offered.telemetry) }
        return RemoteAdded(wanted, fresh.keys.toList(), offered.machine, free.complete)
    }

    /** Every command [server] offers, each at its own address: the ones its profile lists, on a team server this
     *  laptop holds a key for. */
    private suspend fun every(server: String, name: String?): Offered? {
        if (name != null) {
            return names.refused("--as names one command; add the server's commands one at a time to name each")
        }
        val held = asks.profiles.held(server)
        return if (held != null) profiled(server, held) else shared(server)
    }

    /** Every command a LAN share at [server] offers. */
    private suspend fun shared(server: String): Offered? {
        val info = info(server) ?: return null
        val entries = info.commands.associate { it.command to names.at(server, it.command) }
        if (entries.isEmpty()) return names.refused("${info.machine} offers no command")
        return Offered(info.machine, entries)
    }

    /** Every command the profile [read] at [server] lists. */
    private fun profiled(server: String, read: Asked<ServerProfile>): Offered? {
        val offer = names.profiled(server, read) ?: return null
        if (offer.entries.isEmpty()) return names.refused("$server serves no command to ${offer.person}")
        return Offered(server, offer.entries, server, offer.telemetry)
    }

    /** The one command [text] names, once its server has it, with the login and models the address asks for. */
    private suspend fun one(text: String, name: String?): Offered? {
        val address = when (val read = RemoteAddresses.read(text)) {
            is RemoteAddressRead.Refused ->
                return names.refused("$text is not an address a command can run on: ${read.why}")
            is RemoteAddressRead.Read -> read.address
        }
        return info(address.server)?.let { offered(it, address, RemoteConfig(text), name) }
    }

    /** [remote] under [name], or the command's name on the server, once [info] shows it offers what [address] asks
     *  for. */
    private fun offered(info: ShareInfo, address: RemoteAddress, remote: RemoteConfig, name: String?): Offered? {
        val offer = info.commands.firstOrNull { it.command == address.head }
            ?: info.commands.firstOrNull { it.head == address.head }
            ?: return names.refused(notOffered(info, address.head))
        val why = lacking(info.machine, offer, address)
        return if (why != null) names.refused(why) else Offered(info.machine, mapOf((name ?: offer.command) to remote))
    }

    private suspend fun info(server: String): ShareInfo? = when (val read = asks.directory.read(server)) {
        is Asked.Had -> read.value
        is Asked.Refused -> names.refused(read.why)
    }

    private fun notOffered(info: ShareInfo, head: String): String {
        val offers = info.commands.joinToString(", ") { it.command }.ifEmpty { "nothing" }
        return "${info.machine} offers no command named '$head'; it offers $offers"
    }

    /** What [offer] on [machine] lacks that [address] asks for: the login `auth=` names, or a model `models=` lists. */
    private fun lacking(machine: String, offer: ShareOffer, address: RemoteAddress): String? {
        val login = address.auth?.takeIf { it !in offer.logins }
        val model = address.models.firstOrNull { it !in offer.models }
        val logins = offer.logins.joinToString(", ").ifEmpty { "one, which an address can't name" }
        return when {
            login != null -> "${offer.command} on $machine has no login named '$login'; it has $logins"
            model != null ->
                "${offer.command} on $machine has no model named '$model'; it has ${offer.models.joinToString(", ")}"
            else -> null
        }
    }
}
