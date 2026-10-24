// NEW: LAN share, phase 2 (docs/specs/lan-share.md). Importing one sharing machine: join it, keep the token it
// issues in this machine's share store (RemotesStore, never keys.toml), write `[remotes.<name>]`, move any phase-1
// imports from it onto that entry (their per-head keys leave keys.toml), refresh the rows of heads already here,
// and regenerate the others through `splice add`'s own path (AddVerb.addRemote), one line each. One restart and one
// checkup at the end, not one per head. The console's Sharing page imports through this same class, with its own restart.
// Since round 15 of PR #419's review, a machine whose name comes to the key of another machine joined here is written
// under a key of its own (RemoteTopology.keyFor), said, and the other keeps its address, pin, token and commands.
package splice.app.cli.share

import splice.app.AddWiring
import splice.configuration.add.AddDoctor
import splice.configuration.add.DaemonRestart
import splice.configuration.add.RemoteHead
import splice.configuration.add.RemoteModel
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.StatePaths
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
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
import splice.share.remote.RemoteJoin
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteJoined.Joined
import splice.share.remote.RemoteNames
import splice.share.remote.RemoteRelay
import splice.share.remote.RemoteTopology
import splice.share.remote.RemoteTrust
import splice.share.remote.RemotesStore
import splice.terminal.ConfirmPrompt

internal class RemoteImport(
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val env: EnvReader,
    private val confirm: ConfirmPrompt,
    private val restart: DaemonRestart = CliRestart(env, err),
) {
    private val store = RemotesStore(ShareState(StatePaths(envReader = env)).remotesFile)
    private val file = ShareTopologyFile(err, REMOTE_VERB, env)
    private val held = mutableListOf<String>()

    /** True when at least one of [machine]'s heads is usable here afterwards. The join goes only to the certificate
     *  [machine] stated, which is pinned from then on (trust on first contact, RemoteJoin). */
    suspend fun import(machine: RemoteFound): Boolean {
        // A join replaces the token this machine holds there, so a file that would stop the import stops it first.
        val current = file.loadedOrStarter() ?: return false
        val joined = RemoteJoin(ShareHostName().short()).join(machine)
        if (joined is RemoteJoined.Refused) err.line("$REMOTE_VERB: ${joined.why}")
        val ok = joined as? RemoteJoined.Joined ?: return false
        out.line("$GREEN✓$RESET joined ${machine.info.server}, certificate $DIM${ok.pin}$RESET pinned")
        val target = target(current, machine, ok)
        // The token goes first: a provider that names the machine finds it on its first turn.
        return kept(target.name, ok.token) && imported(machine, target, current)
    }

    /** Where [machine] is written here: under its `[remotes]` key (RemoteTopology.keyFor), said when another machine
     *  of its name keeps the one its name gives, and at the address it is reached at. */
    private fun target(current: Topology, machine: RemoteFound, joined: Joined): RemoteTarget {
        val server = machine.info.server
        val host = machine.reachable()
        val url = joined.http.base(host, machine.port)
        val name = RemoteTopology(current).keyFor(server, url, joined.pin)
        current.remotes[RemoteNames.of(server)]?.takeIf { name != RemoteNames.of(server) }?.let { other ->
            out.line("  $server at $url is not the machine of that name at ${other.url}; it is joined here as $name")
        }
        return RemoteTarget(name, url, server, joined, host)
    }

    private suspend fun imported(machine: RemoteFound, target: RemoteTarget, current: Topology): Boolean {
        val (name, url, server) = target
        val spellings = setOf(machine.address, target.host, server, "$server.local", "$server.lan")
        val legacy = RemoteTopology(current).phaseOne(spellings)
        val pinned = RemoteTopology(current).joined(name, url, target.joined.pin)
        val moved = RemoteTopology(pinned).migrated(legacy, name, url)
        val here = machine.info.heads.mapNotNull { shared ->
            RemoteTopology(moved).headFor(name, shared.key)?.let { it to shared }
        }
        val wanted = here.fold(moved) { topology, (key, shared) -> RemoteTopology(topology).synced(key, shared) }
        if (!file.saved(wanted)) return false
        forgetKeys(current, legacy.keys)
        here.forEach { (key, _) -> out.line("$GREEN✓$RESET ${commandOf(wanted, key)}: already here, rows refreshed") }
        val added = machine.info.heads.filter { shared -> here.none { it.second == shared } }
            .count { shared -> added(target, shared) }
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

    private suspend fun added(target: RemoteTarget, shared: SharedHead): Boolean {
        val server = target.server
        val topology = file.loaded() ?: return false
        val (key, command) = localNames(topology, shared.key, shared.command, target) ?: return false
        val head = RemoteHead(
            remote = target.name,
            remoteKey = shared.key,
            key = key,
            command = command,
            baseUrl = RemoteRelay.url(target.url, shared.key),
            models = shared.models.map {
                RemoteModel(it.id, it.label, it.contextWindow, it.slots, it.compactionReserve, it.rates)
            },
            family = shared.family,
        )
        held.clear()
        // The add's checks reach the relay over the certificate this machine just pinned, the one its head will trust.
        val verb = AddWiring.add(
            restart = DaemonRestart { true },
            doctor = AddDoctor { true },
            output = TerminalOutput { held += it },
            tls = RemoteTrust(target.joined.pin, server).context,
        )
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
     *  here or the command is another head's, `<name>-<machine's key>` once the person agrees. Null skips the head. */
    private fun localNames(
        topology: Topology,
        key: String,
        command: String,
        target: RemoteTarget,
    ): Pair<String, String>? {
        val keyTaken = key in topology.heads || key in topology.providers
        val taken = keyTaken || topology.heads.any { (k, head) -> (head.claude.command ?: k) == command }
        if (!taken) return key to command
        val suffix = target.name
        val server = target.server
        return if (confirm("'$command' is already a command here. Add $server's as '$command-$suffix'?", true)) {
            "$key-$suffix" to "$command-$suffix"
        } else {
            out.line("${YELLOW}skipped$RESET $command from $server")
            null
        }
    }

    /** One restart for every head the import wrote, and one checkup when it added any. */
    private fun settle(changed: Boolean, checkup: Boolean) {
        if (changed) restart()
        if (checkup) out.line("  checkup     ${CYAN}splice doctor$RESET")
    }

    private fun commandOf(topology: Topology, key: String): String = topology.heads[key]?.claude?.command ?: key
}

/** The machine a head is imported from: its `[remotes]` [name], relay [url], own [server] name, the join, and the
 *  [host] it is reached at. */
private data class RemoteTarget(
    val name: String,
    val url: String,
    val server: String,
    val joined: Joined,
    val host: String,
)

internal const val REMOTE_VERB = "splice remote"
