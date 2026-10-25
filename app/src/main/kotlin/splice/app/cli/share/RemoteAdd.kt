// NEW: LAN share (docs/specs/lan-share.md). `splice remote add`: an address is checked against the server it names
// before anything is kept, so a typo is said now, never found out as a launch that fails or runs on the wrong login.
// The server must answer, offer the command, and have the login `auth=` names and every model `models=` lists. An
// address naming the server alone adds every command it offers. A name this machine already runs (a head here, or
// another address under that name) is refused with how to give the new one another: `--as <command>`. A relay the first
// LAN-share build wrote under that name is the one exception: the address replaces it, and its share token leaves
// keys.toml in the same add.
package splice.app.cli.share

import splice.core.terminal.CYAN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology
import splice.share.ShareDirectory
import splice.share.ShareDirectoryRead
import splice.share.ShareInfo
import splice.share.ShareOffer
import java.net.URI

/** What an add wrote: the file as it now is, the [commands] added, the [machine] they run on, and whether every one
 *  asked for was ([complete]); one that was not is said already. */
internal data class RemoteAdded(
    val topology: Topology,
    val commands: List<String>,
    val machine: String,
    val complete: Boolean,
)

/** The entries an address asks for, by command, and the [machine] that serves them. */
private data class Offered(val machine: String, val entries: Map<String, RemoteConfig>)

internal class RemoteAdd(
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val file: ShareTopologyFile,
    private val directory: ShareDirectory,
    /** The first LAN-share build's relays, which an address under one's name replaces. */
    private val relays: FirstBuildRelays,
) {
    // A name a command link can take: a bare file name, as a head's key is.
    private val commandName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    // splice's own command. `claude` may name another machine's command: the wrapped `claude` then launches there
    // (LaunchRoutes), and its link stays Claude Code's, which no remote makes, replaces or removes (Topology.remoteLinks).
    private val reserved = setOf("splice")

    /** [text] added, as [name] when given and as the server calls it otherwise; null, said, when nothing was. */
    suspend fun add(text: String, name: String?): RemoteAdded? {
        val topology = file.loadedOrStarter() ?: return null
        val offered = asked(text.trim().let { if ("://" in it) it else "http://$it" }, name) ?: return null
        return kept(topology, offered)
    }

    private suspend fun asked(address: String, name: String?): Offered? =
        if (serverOnly(address)) every(address.trimEnd('/'), name) else one(address, name)

    /** [offered] written into [topology], each entry whose name is free here; one that is not is said. A first-build
     *  relay under an entry's name is replaced by it. */
    private fun kept(topology: Topology, offered: Offered): RemoteAdded? {
        val refusals = offered.entries.mapNotNull { (command, remote) ->
            taken(topology, command, remote)?.let { command to it }
        }.toMap()
        refusals.values.forEach { err.line("$REMOTE_VERB: $it") }
        val fresh = offered.entries - refusals.keys
        val replacing = relays.replacing(topology, fresh.keys)
        val wanted = replacing.topology.let { it.copy(remote = it.remote + fresh) }
        if (fresh.isEmpty() || !file.saved(wanted)) return null
        out.line("added to ${file.path}: ${fresh.keys.joinToString(", ")}")
        relays.replaced(topology, replacing)
        return RemoteAdded(wanted, fresh.keys.toList(), offered.machine, refusals.isEmpty())
    }

    /** Whether [address] names a server and nothing after it: `http://torad-server.lan:3097`. */
    private fun serverOnly(address: String): Boolean = try {
        val uri = URI.create(address)
        uri.rawPath.orEmpty().trim('/').isEmpty() && uri.rawQuery == null && uri.rawFragment == null
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Every command [server] offers, each at its own address. */
    private suspend fun every(server: String, name: String?): Offered? {
        if (name != null) {
            return refused("--as names one command; add the server's commands one at a time to name each")
        }
        val info = info(server) ?: return null
        val entries = info.commands.associate { it.command to RemoteConfig("$server/${it.command}") }
        return if (entries.isEmpty()) refused("${info.machine} offers no command") else Offered(info.machine, entries)
    }

    /** The one command [text] names, once its server has it, with the login and models the address asks for. */
    private suspend fun one(text: String, name: String?): Offered? {
        val address = when (val read = RemoteAddresses.read(text)) {
            is RemoteAddressRead.Refused -> return refused("$text is not an address a command can run on: ${read.why}")
            is RemoteAddressRead.Read -> read.address
        }
        return info(address.server)?.let { offered(it, address, RemoteConfig(text), name) }
    }

    /** [remote] under [name], or the command's name on the server, once [info] shows it offers what [address] asks
     *  for. */
    private fun offered(info: ShareInfo, address: RemoteAddress, remote: RemoteConfig, name: String?): Offered? {
        val offer = info.commands.firstOrNull { it.command == address.head }
            ?: info.commands.firstOrNull { it.head == address.head }
            ?: return refused(notOffered(info, address.head))
        val why = lacking(info.machine, offer, address)
        return if (why != null) refused(why) else Offered(info.machine, mapOf((name ?: offer.command) to remote))
    }

    private suspend fun info(server: String): ShareInfo? = when (val read = directory.read(server)) {
        is ShareDirectoryRead.Read -> read.info
        is ShareDirectoryRead.Refused -> refused(read.why)
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

    /** Why [command] can't be kept for [remote] here, or null when it can. The same address under the same name is
     *  kept again, which links it anew. */
    private fun taken(topology: Topology, command: String, remote: RemoteConfig): String? {
        val elsewhere = "add this one under another name with $CYAN--as <command>$RESET"
        val held = topology.remote[command]
        return when {
            !commandName.matches(command) -> "${quoted(command)} can't be a command's name; $elsewhere"
            held == remote -> null
            held != null -> "${quoted(command)} already runs on ${held.url}; $elsewhere, or take that one out first " +
                "with ${CYAN}splice remote remove $command$RESET"
            command in reserved || relays.runsHere(topology, command) ->
                "${quoted(command)} is already a command here; $elsewhere"
            else -> null
        }
    }

    private fun quoted(command: String): String = "'$command'"

    private fun <T> refused(why: String): T? {
        err.line("$REMOTE_VERB: $why")
        return null
    }
}
