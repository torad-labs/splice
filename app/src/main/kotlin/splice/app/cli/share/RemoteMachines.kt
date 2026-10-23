// NEW: LAN share, phase 2 (docs/specs/lan-share.md). `splice remote list | sync | remove`: the machines this one
// joined, as splice.toml's `[remotes]` and this machine's share store hold them. `sync` rewrites each imported head's
// rows, pin and tiers as its machine serves them now; `remove` drops the machine, its heads, their command links and
// its token. Both restart a running daemon once, which then serves what the file says.
package splice.app.cli.share

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.app.cli.peer.PeerExit
import splice.app.head.WrapperBinSource
import splice.app.head.WrapperLinks
import splice.core.config.StatePaths
import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import splice.core.topology.DEFAULT_SHARE_PORT
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.share.ShareInfo
import splice.share.ShareState
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteTrust
import splice.share.remote.RemoteTopology
import splice.share.remote.RemotesStore
import java.net.URI
import java.util.concurrent.TimeUnit

internal class RemoteMachines(
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val env: EnvReader,
) {
    private val firstContact = RemoteHttp(RemoteTrust(null, "a sharing machine"))
    private val file = ShareTopologyFile(err, REMOTE_VERB, env)
    private val store = RemotesStore(ShareState(StatePaths()).remotesFile)

    /** What [host]:[port] offers, asked through [http] (a first contact unless the machine is pinned), or null,
     *  said, when nothing there answers as a sharing splice or its certificate is not the pinned one. */
    fun info(host: String, port: Int, http: RemoteHttp = firstContact): ShareInfo? {
        val asked = Cancellables.runCatchingCancellable { http.info(host, port).get(ANSWER_WAIT_S, TimeUnit.SECONDS) }
        val info = asked.fold(onSuccess = { it }, onFailure = { null })
        if (info == null) {
            val hint = "is ${CYAN}splice share on$RESET there?"
            val at = http.url(host, port, "").authority
            val refused = asked.exceptionOrNull()?.let(http::refusal)
            err.line("$REMOTE_VERB: ${refused ?: "nothing at $at answers as a sharing splice ($hint)"}")
        }
        return info
    }

    fun list(): Int {
        val topology = file.loaded() ?: return PeerExit.REFUSED
        if (topology.remotes.isEmpty()) out.line("no machines joined here. ${CYAN}splice remote$RESET finds them.")
        val tokens = Cancellables.runCatchingCancellable { store.names().toSet() }
            .fold(onSuccess = { it }, onFailure = { emptySet() })
        topology.remotes.forEach { (name, remote) ->
            val token = if (name in tokens) "" else "  ${YELLOW}no token here; run ${CYAN}splice remote$RESET"
            out.line("$BOLD$name$RESET $DIM${remote.url}$RESET$token")
            RemoteTopology(topology).heads(name).forEach { (key, head) ->
                out.line("  ${(head.claude.command ?: key).padEnd(REMOTE_COMMAND_PAD)} $DIM${head.pinnedModel}$RESET")
            }
        }
        return PeerExit.DONE
    }

    /** Each head's rows, pin and tiers as its machine serves them now; a head the machine stopped offering is said. */
    fun sync(only: String?): Int {
        val topology = file.loaded() ?: return PeerExit.REFUSED
        val names = only?.let { listOf(it) } ?: topology.remotes.keys.toList()
        names.firstOrNull { it !in topology.remotes }?.let { return unknown(it) }
        val wanted = names.fold(topology) { current, name -> synced(current, name) }
        return when {
            wanted == topology -> PeerExit.DONE.also { out.line("$GREEN✓$RESET every row is current") }
            !file.saved(wanted) -> PeerExit.REFUSED
            else -> {
                out.line("$GREEN✓$RESET rows refreshed in ${file.path}")
                restartIfUp()
            }
        }
    }

    /** [topology] with [name]'s heads as it serves them now; unchanged when it does not answer. */
    private fun synced(topology: Topology, name: String): Topology {
        val remote = topology.remotes.getValue(name)
        val url = remoteUri(remote.url) ?: return topology
        val pinned = RemoteHttp(RemoteTrust(remote.fingerprint.orEmpty(), name))
        val port = url.port.takeIf { it > 0 } ?: DEFAULT_SHARE_PORT
        val info = info(url.host.trim('[', ']'), port, pinned) ?: return topology
        val edits = RemoteTopology(topology)
        val add = "${CYAN}splice remote add ${url.host}$RESET"
        info.heads.filter { edits.headFor(name, it.key) == null }
            .forEach { out.line("  ${it.command}: new on $name; $add imports it") }
        val offered = info.heads.mapNotNull { edits.headFor(name, it.key) }.toSet()
        edits.heads(name).keys.filterNot { it in offered }
            .forEach { out.line("  $YELLOW!$RESET $it: $name no longer offers it") }
        return info.heads.fold(topology) { current, shared ->
            RemoteTopology(current).headFor(name, shared.key)?.let { RemoteTopology(current).synced(it, shared) }
                ?: current
        }
    }

    /** The machine, the heads relayed to it, their command links and its token. The daemon restarts to drop them. */
    fun remove(name: String): Int {
        val topology = file.loaded() ?: return PeerExit.REFUSED
        if (name !in topology.remotes) return unknown(name)
        val kept = RemoteTopology(topology).without(name)
        return if (file.saved(kept)) removed(name, topology, kept) else PeerExit.REFUSED
    }

    private fun removed(name: String, topology: Topology, kept: Topology): Int {
        val heads = RemoteTopology(topology).heads(name)
        val links = WrapperLinks(WrapperBinSource { null }, LogSink { line -> err.line("$REMOTE_VERB: $line") })
        val commands = heads.map { (key, head) -> head.claude.command ?: key }
        commands.filter { command -> kept.heads.none { (k, h) -> (h.claude.command ?: k) == command } }
            .forEach(links::unlink)
        Cancellables.runCatchingCancellable { store.remove(name) }.onFailure {
            err.line("$REMOTE_VERB: its token stays in the share store: ${SafeFailureText.render(it)}")
        }
        val with = commands.joinToString(", ", prefix = " with ").takeIf { commands.isNotEmpty() }.orEmpty()
        out.line("$GREEN✓$RESET $name removed$with")
        return restartIfUp()
    }

    private fun restartIfUp(): Int {
        if (!AdminSupport.daemonUp(AdminSupport.controlPort(env))) return PeerExit.DONE
        if (LifecycleWiring.restart()) return PeerExit.DONE
        err.line("$REMOTE_VERB: the restart failed; run ${CYAN}splice restart$RESET")
        return PeerExit.REFUSED
    }

    /** A url findings refuse at every write and start, said here too for a file edited by hand since. */
    private fun remoteUri(url: String): URI? = Cancellables.runCatchingCancellable { URI(url) }.fold(
        onSuccess = { it.takeIf { uri -> uri.host != null } },
        onFailure = { null },
    ) ?: null.also { err.line("$REMOTE_VERB: '$url' is not an address with a host") }

    private fun unknown(name: String): Int {
        err.line("$REMOTE_VERB: no machine named '$name' here (see ${CYAN}splice remote list$RESET)")
        return PeerExit.REFUSED
    }
}

// why: `remote add` waits this long for /share/v1/info, longer than RemoteHttp's connect and request timeouts
// together (0.8 s + 3 s), so the request's own timeout answers first and this only bounds a reply that never comes.
private const val ANSWER_WAIT_S = 5L
