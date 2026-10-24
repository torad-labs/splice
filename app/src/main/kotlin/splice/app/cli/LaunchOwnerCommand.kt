// NEW: macOS parity — the launcher's child writes a non-secret declaration of its parent's exact birth.
package splice.app.cli

import splice.core.config.StatePaths
import splice.core.process.LaunchDeclaration
import splice.core.process.LaunchOwners
import splice.core.process.LaunchTerminal
import splice.core.topology.Topology
import splice.topology.TopologyLoader
import java.net.URI

// why: the kind and origin follow the pid, head and base URL.
private const val KIND_AT = 3

// why: the pane and socket follow the pid, head, base URL, kind and origin.
private const val TERMINAL_AT = 5

/** The shim's non-secret declaration, written by a child JVM immediately before its parent execs. */
internal class LaunchOwnerCommand(private val args: List<String>) : Command() {
    override suspend fun run(): Int {
        val (pidText, selector, baseUrl) = args
        val kind = args[KIND_AT]
        val origin = args[KIND_AT + 1]
        // A pane that is not one is no terminal splice may drive: the launch is still recorded, without it.
        val terminal = args.drop(TERMINAL_AT).takeIf { it.size == 2 }
            ?.let { (pane, socket) -> LaunchTerminal(pane, socket) }?.takeIf { it.valid }
        val pid = pidText.toLong()
        if (ProcessHandle.current().parent().map { it.pid() }.orElse(-1L) != pid) return 1
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val head = resolveHead(topology, selector, baseUrl, kind) ?: return 1
        LaunchOwners(StatePaths().stateDir).write(pid, LaunchDeclaration(head, baseUrl, kind, origin, terminal))
        return 0
    }

    private fun resolveHead(topology: Topology, selector: String, baseUrl: String, kind: String): String? {
        if (kind != "session") return topology.resolveHeadKey(selector)
        // A wrapped claude basename need not name a head. The daemon-authored URL does.
        val url = URI.create(baseUrl)
        if (url.host != "127.0.0.1" || url.scheme !in setOf("http", "https")) return null
        return topology.heads.entries.singleOrNull { it.value.port == url.port }?.key
    }
}
