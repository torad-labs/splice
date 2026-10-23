// NEW: LAN share, phase 2 (docs/specs/lan-share.md). Importing one sharing machine: join it, keep the token it
// issues in this machine's share store (RemotesStore, never keys.toml), write `[remotes.<name>]`, move any phase-1
// imports from it onto that entry (their per-head keys leave keys.toml), refresh the rows of heads already here,
// and regenerate the others through `splice add`'s own path (AddVerb.addRemote), one line each. One restart and one
// checkup at the end, not one per head.
package splice.app.cli.share

import splice.app.AddWiring
import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.configuration.add.AddDoctor
import splice.configuration.add.DaemonRestart
import splice.configuration.add.RemoteHead
import splice.configuration.add.RemoteModel
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.StatePaths
import splice.core.terminal.CYAN
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.share.ShareHostName
import splice.share.ShareState
import splice.share.SharedHead
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteRelay
import splice.share.remote.RemoteTopology
import splice.share.remote.RemotesStore
import splice.terminal.ConfirmPrompt
import java.net.InetAddress

internal class RemoteImport(
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val env: EnvReader,
    private val confirm: ConfirmPrompt,
) {
    private val http = RemoteHttp()
    private val store = RemotesStore(ShareState(StatePaths()).remotesFile)
    private val file = ShareTopologyFile(err, REMOTE_VERB, env)
    private val held = mutableListOf<String>()
    private val verb = AddWiring.add(
        restart = DaemonRestart { true },
        doctor = AddDoctor { true },
        output = TerminalOutput { held += it },
    )

    /** True when at least one of [machine]'s heads is usable here afterwards. */
    suspend fun import(machine: RemoteFound): Boolean {
        val name = RemoteNames.of(machine.info.server)
        val joined = Cancellables.runCatchingCancellable { http.join(machine.address, machine.port, self()) }
            .getOrElse { failure ->
                val why = SafeFailureText.render(failure)
                err.line("$REMOTE_VERB: ${machine.info.server} did not let this machine join: $why")
                return false
            }
        // The token goes first: a provider that names the machine finds it on its first turn.
        val current = file.loaded()?.takeIf { kept(name, joined.token) } ?: return false
        return imported(machine, name, current)
    }

    private suspend fun imported(machine: RemoteFound, name: String, current: Topology): Boolean {
        val server = machine.info.server
        val host = reachableName(machine)
        val url = http.base(host, machine.port)
        val spellings = setOf(machine.address, host, server, "$server.local", "$server.lan")
        val legacy = RemoteTopology(current).phaseOne(spellings)
        val moved = RemoteTopology(RemoteTopology(current).joined(name, url, null)).migrated(legacy, name, url)
        val here = machine.info.heads.mapNotNull { shared ->
            RemoteTopology(moved).headFor(name, shared.key)?.let { it to shared }
        }
        val wanted = here.fold(moved) { topology, (key, shared) -> RemoteTopology(topology).synced(key, shared) }
        if (!file.saved(wanted)) return false
        forgetKeys(current, legacy.keys)
        here.forEach { (key, _) -> out.line("$GREEN✓$RESET ${commandOf(wanted, key)}: already here, rows refreshed") }
        val added = machine.info.heads.filter { shared -> here.none { it.second == shared } }
            .count { shared -> added(name, url, server, shared) }
        settle(changed = wanted != current || added > 0, checkup = added > 0)
        return here.isNotEmpty() || added > 0
    }

    private fun kept(name: String, token: String): Boolean =
        Cancellables.runCatchingCancellable { store.save(name, token) }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                err.line("$REMOTE_VERB: the token from $name was not kept: ${SafeFailureText.render(failure)}")
                false
            },
        )

    /** Phase 1 kept each imported head's token in keys.toml under its own name; nothing reads those now. */
    private fun forgetKeys(before: Topology, providers: Set<String>) {
        val keys = KeyStore(KeyStorePath.defaultPath(env))
        providers.forEach { key ->
            val envVar = before.providers[key]?.auth?.effectiveApiKeyEnv(key) ?: return@forEach
            Cancellables.runCatchingCancellable { keys.unset(envVar) }
                .onFailure { out.line("  $YELLOW!$RESET $envVar stays in ${keys.path}; nothing reads it now") }
        }
        if (providers.isNotEmpty()) out.line("$GREEN✓$RESET moved ${providers.joinToString(", ")} onto the remote")
    }

    private suspend fun added(name: String, url: String, server: String, shared: SharedHead): Boolean {
        val topology = file.loaded() ?: return false
        val (key, command) = localNames(topology, shared.key, shared.command, server) ?: return false
        val head = RemoteHead(
            remote = name,
            remoteKey = shared.key,
            key = key,
            command = command,
            baseUrl = RemoteRelay.url(url, shared.key),
            models = shared.models.map {
                RemoteModel(it.id, it.label, it.contextWindow, it.slots, it.compactionReserve, it.rates)
            },
            family = shared.family,
        )
        held.clear()
        val ok = verb.addRemote(head, env)
        if (ok) {
            out.line("$GREEN✓$RESET $command: ${shared.models.firstOrNull()?.label.orEmpty()} on $server")
            // The add's own warnings (a wrapper it could not link) still reach the person; its checks do not.
            held.filter { "$YELLOW!$RESET" in it }.forEach(out::line)
        } else {
            held.forEach(err::line)
        }
        return ok
    }

    /** The key and command a shared head is written under here: its own, or, when the key names a head or a provider
     *  here or the command is another head's, `<name>-<server>` once the person agrees. Null skips the head. */
    private fun localNames(topology: Topology, key: String, command: String, server: String): Pair<String, String>? {
        val keyTaken = key in topology.heads || key in topology.providers
        val taken = keyTaken || topology.heads.any { (k, head) -> (head.claude.command ?: k) == command }
        if (!taken) return key to command
        val suffix = RemoteNames.of(server)
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

    /** One restart for every head the import wrote, and one checkup when it added any. */
    private fun settle(changed: Boolean, checkup: Boolean) {
        if (changed && AdminSupport.daemonUp(AdminSupport.controlPort(env))) {
            val restarted = LifecycleWiring.restart()
            if (!restarted) err.line("$REMOTE_VERB: the restart failed; run ${CYAN}splice restart$RESET")
        }
        if (checkup) out.line("  checkup     ${CYAN}splice doctor$RESET")
    }

    private fun commandOf(topology: Topology, key: String): String = topology.heads[key]?.claude?.command ?: key

    private fun self(): String = ShareHostName().short()
}

/** A machine's name as a `[remotes]` key and a command suffix: lower case, anything but letters, digits and dashes as
 *  a dash. */
internal object RemoteNames {
    fun of(server: String): String =
        server.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "remote" }
}

internal const val REMOTE_VERB = "splice remote"
