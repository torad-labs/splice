// NEW: LAN share (docs/specs/lan-share.md). The first LAN-share build's relays on a node (RetiredRelays), as `splice
// remote add` replaces them: an address added under a relay's name takes its place, and the relay's provider and share
// token go in the same add, so the node keeps no credential and boot has nothing of that build left to refuse.
package splice.app.cli.share

import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.topology.RetiredRelays
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText

/** What replacing came to: the file without the [relays] it took out, ready for the addresses that replace them. */
internal data class Replacing(val topology: Topology, val relays: Set<String>)

internal class FirstBuildRelays(
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    env: EnvReader,
) {
    private val keys = KeyStore(KeyStorePath.defaultPath(env), LogSink { err.line(it.trimEnd()) })

    /** Whether a head here that is not a first-build relay already runs as [command]. */
    fun runsHere(topology: Topology, command: String): Boolean {
        val relays = RetiredRelays.of(topology)
        return topology.resolveHeadKeys(command).any { it !in relays }
    }

    /** [topology] without each first-build relay that runs as one of [commands]. */
    fun replacing(topology: Topology, commands: Set<String>): Replacing {
        val relays = RetiredRelays.of(topology)
        val replaced = commands.flatMap(topology::resolveHeadKeys).filter { it in relays }.toSet()
        return Replacing(RetiredRelays.without(topology, replaced), replaced)
    }

    /** Once [replacing], made from [before], is written: the share token of each provider it took out leaves keys.toml,
     *  and the replacement is said once. */
    fun replaced(before: Topology, replacing: Replacing) {
        if (replacing.relays.isEmpty()) return
        (before.providers - replacing.topology.providers.keys).forEach { (key, provider) ->
            if (!provider.auth.isApiKey) return@forEach
            Cancellables.runCatchingCancellable { keys.unset(provider.auth.effectiveApiKeyEnv(key)) }.onFailure {
                err.line("$REMOTE_VERB: $key's share token is still in ${keys.path}: ${SafeFailureText.render(it)}")
            }
        }
        val commands = replacing.relays.joinToString(", ")
        out.line("replaced the first LAN-share build's relay for $commands, and took its share token out of keys.toml")
    }
}
