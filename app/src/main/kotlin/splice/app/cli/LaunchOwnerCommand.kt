// NEW: macOS parity — the launcher's child writes a non-secret declaration of its parent's exact birth.
package splice.app.cli

import splice.core.config.StatePaths
import splice.core.process.LaunchOwners
import splice.core.topology.Topology
import splice.topology.TopologyLoader
import java.net.URI

/** The shim's non-secret declaration, written by a child JVM immediately before its parent execs. */
internal class LaunchOwnerCommand(private val args: List<String>) : Command() {
    override suspend fun run(): Int {
        val (pidText, selector, baseUrl) = args
        val (kind, origin) = args.takeLast(2)
        val pid = pidText.toLong()
        if (ProcessHandle.current().parent().map { it.pid() }.orElse(-1L) != pid) return 1
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val head = resolveHead(topology, selector, baseUrl, kind) ?: return 1
        LaunchOwners(StatePaths().stateDir).write(pid, head, baseUrl, kind, origin)
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
