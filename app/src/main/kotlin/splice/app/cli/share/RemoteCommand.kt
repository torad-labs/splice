// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). `splice remote`, the using machine's
// side: find the machines on this network that share heads (RemoteDiscovery), ask per machine, join it, and
// regenerate each of its heads here through `splice add`'s own path (AddVerb.addRemote): the same command, the
// same models and tiers, every turn relayed to that machine and run on its logins. One restart at the end.
// A head imported before keeps its row and only takes the new token, which is how a revoked machine comes back.
package splice.app.cli.share

import splice.app.AddWiring
import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.app.cli.peer.PeerExit
import splice.app.share.SHARE_HEADS_PATH
import splice.app.share.ShareHostName
import splice.configuration.add.DaemonRestart
import splice.configuration.add.RemoteHead
import splice.configuration.add.RemoteModel
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
import splice.core.util.SafeFailureText
import splice.terminal.ConfirmPrompt
import splice.terminal.ConsoleConfirm
import splice.topology.TopologyLoader
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

internal const val REMOTE_USAGE = "[scan] | add <host[:port]> | list"

internal class RemoteCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
    private val confirm: ConfirmPrompt = ConsoleConfirm(),
) {
    private val http = RemoteHttp()

    suspend fun run(args: List<String>): Int = when (args.firstOrNull()) {
        null, "scan" -> if (args.size <= 1) scan() else usage()
        "add" -> args.getOrNull(1)?.takeIf { args.size == 2 }?.let { addHost(it) } ?: usage()
        "list" -> if (args.size == 1) list() else usage()
        else -> usage()
    }

    private suspend fun scan(): Int {
        out.line("${DIM}looking for machines that share splice heads on your networks…$RESET")
        val self = ShareHostName().short()
        val found = RemoteDiscovery(http).scan(DEFAULT_SHARE_PORT).filterNot { it.info.server == self }
        if (found.isEmpty()) {
            out.line("no machine on your networks shares heads.")
            out.line("  on the machine with the logins: ${CYAN}splice share on$RESET")
            out.line("  across Tailscale or another network: ${CYAN}splice remote add <host>$RESET")
            return PeerExit.REFUSED
        }
        var imported = false
        found.forEach { machine ->
            describe(machine)
            if (confirm("Use ${machine.info.server}'s heads here?", true)) imported = import(machine) || imported
        }
        return if (imported) PeerExit.DONE else PeerExit.REFUSED
    }

    private suspend fun addHost(target: String): Int {
        val (host, port) = authority(target) ?: return usage()
        val info = Cancellables.runCatchingCancellable { http.info(host, port).get(ANSWER_WAIT_S, TimeUnit.SECONDS) }
            .fold(onSuccess = { it }, onFailure = { null })
        if (info == null) {
            val hint = "is ${CYAN}splice share on$RESET there?"
            val at = http.url(host, port, "").authority
            err.line("splice remote: nothing at $at answers as a sharing splice ($hint)")
            return PeerExit.REFUSED
        }
        val machine = RemoteFound(host, port, info)
        describe(machine)
        val imported = confirm("Use ${info.server}'s heads here?", true) && import(machine)
        return if (imported) PeerExit.DONE else PeerExit.REFUSED
    }

    /** [target] as the host and port to ask: `host`, `host:port`, `[v6]`, `[v6]:port`, or a bare IPv6 address, whose
     *  colons are all its own, read as a URI's authority. The host comes back without brackets, which RemoteHttp.url
     *  puts back once (PR #247's fourth review: `[fd00::1]:3097` was bracketed twice). Null for any other target. */
    private fun authority(target: String): Pair<String, Int>? {
        val bare = target.count { it == ':' } > 1 && !target.startsWith("[")
        // A target that is no URI authority at all is answered with the usage, as one with a path or a bad port is.
        val uri = Cancellables.runCatchingCancellable { URI("http://${if (bare) "[$target]" else target}") }
            .fold(onSuccess = { it }, onFailure = { null })
        val host = uri?.takeIf { it.rawPath.isEmpty() }?.host?.removeSurrounding("[", "]")
        val port = uri?.port?.takeIf { it != -1 } ?: DEFAULT_SHARE_PORT
        return host?.takeIf { it.isNotBlank() && port in 1..UShort.MAX_VALUE.toInt() }?.let { it to port }
    }

    private fun describe(machine: RemoteFound) {
        out.line("")
        out.line("$BOLD${machine.info.server}$RESET ${DIM}at ${machine.address}, splice ${machine.info.version}$RESET")
        machine.info.heads.forEach { head ->
            val models = head.models.joinToString(", ") { it.label }
            out.line("  ${head.command.padEnd(COMMAND_PAD)} $DIM$models$RESET")
        }
    }

    /** True when at least one head is usable here afterwards. */
    private suspend fun import(machine: RemoteFound): Boolean {
        val server = machine.info.server
        val self = ShareHostName().short()
        val joined = Cancellables.runCatchingCancellable { http.join(machine.address, machine.port, self) }
            .getOrElse { failure ->
                err.line("splice remote: $server did not let this machine join: ${SafeFailureText.render(failure)}")
                return false
            }
        val host = reachableName(machine)
        val verb = AddWiring.add(restart = DaemonRestart { true })
        var added = false
        var ready = false
        machine.info.heads.forEach { shared ->
            val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
            val baseUrl = http.url(host, machine.port, "$SHARE_HEADS_PATH/${shared.key}").toString()
            val already = topology.heads.entries.firstOrNull { (_, head) ->
                topology.providers[head.provider]?.baseUrl == baseUrl
            }
            if (already != null) {
                if (verb.renewRemote(topology, already.key, joined.token, env)) {
                    out.line("$GREEN✓$RESET ${already.key}: already here; its token on $server is renewed")
                    ready = true
                }
                return@forEach
            }
            val (key, command) = localNames(topology, shared.key, shared.command, server) ?: return@forEach
            val head = RemoteHead(
                server = server,
                remoteKey = shared.key,
                key = key,
                command = command,
                baseUrl = baseUrl,
                token = joined.token,
                models = shared.models.map {
                    RemoteModel(it.id, it.label, it.contextWindow, it.slots, it.compactionReserve, it.rates)
                },
            )
            if (verb.addRemote(head, env)) {
                added = true
                ready = true
            }
        }
        if (added) restartIfUp()
        return ready
    }

    /** The key and command a shared head is written under here: its own, or, when the key names a head or a provider
     *  here or the command is another head's, `<name>-<server>` once the person agrees. Null skips the head. */
    private fun localNames(topology: Topology, key: String, command: String, server: String): Pair<String, String>? {
        val keyTaken = key in topology.heads || key in topology.providers
        val taken = keyTaken || topology.heads.any { (k, head) -> (head.claude.command ?: k) == command }
        if (!taken) return key to command
        val suffix = server.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        return if (confirm("'$command' is already a command here. Add $server's as '$command-$suffix'?", true)) {
            "$key-$suffix" to "$command-$suffix"
        } else {
            out.line("${YELLOW}skipped$RESET $command from $server")
            null
        }
    }

    /** The server's own name when it resolves here to the address that answered (a DHCP move then follows the
     *  name), else that address. */
    private fun reachableName(machine: RemoteFound): String {
        if (machine.address.none { it.isLetter() } || ':' in machine.address) {
            val server = machine.info.server
            return listOf("$server.local", "$server.lan", server).firstOrNull { name ->
                Cancellables.runCatchingCancellable {
                    InetAddress.getAllByName(name).any { it.hostAddress == machine.address }
                }.fold(onSuccess = { it }, onFailure = { false })
            } ?: machine.address
        }
        return machine.address
    }

    private fun restartIfUp() {
        if (!AdminSupport.daemonUp(AdminSupport.controlPort(env))) return
        if (!LifecycleWiring.restart()) err.line("splice remote: the restart failed; run ${CYAN}splice restart$RESET")
    }

    private fun list(): Int {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
        val relayed = topology.heads.filter { (_, head) ->
            topology.providers[head.provider]?.baseUrl?.contains(SHARE_HEADS_PATH) == true
        }
        if (relayed.isEmpty()) out.line("no heads from other machines here. ${CYAN}splice remote$RESET finds them.")
        relayed.forEach { (key, head) ->
            val base = topology.providers[head.provider]?.baseUrl.orEmpty()
            out.line("${(head.claude.command ?: key).padEnd(COMMAND_PAD)} $DIM$base$RESET")
        }
        return PeerExit.DONE
    }

    private fun usage(): Int {
        err.line("usage: splice remote $REMOTE_USAGE")
        return PeerExit.BAD_USAGE
    }
}

// why: the command column of a listing: `claude-` and a head name of up to eleven characters line their models
// up, and a longer command pushes only its own row's models to the right.
private const val COMMAND_PAD = 18

// why: `remote add` waits this long for /share/v1/info, longer than RemoteHttp's connect and request timeouts
// together (0.8 s + 3 s), so the request's own timeout answers first and this only bounds a reply that never comes.
private const val ANSWER_WAIT_S = 5L
