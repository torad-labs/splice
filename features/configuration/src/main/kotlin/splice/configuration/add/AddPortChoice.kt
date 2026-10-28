// The port decision of splice add, separate from the candidate assembly (failure-handling slice 8).
package splice.configuration.add

import splice.core.topology.Topology
import java.net.URI
import java.net.URISyntaxException

// why: 3099 is the established first head listener; further heads start above the ports already declared.
private const val FIRST_HEAD_PORT = 3099

// why: TCP listener ports are unsigned 16-bit; a range must never wrap past this ceiling.
internal const val LAST_HEAD_PORT = 65_535

/** Selects a head port from the bounded range, excluding declared listeners and proving each
 *  remaining candidate binds on the same IPv4 loopback address the head will use. */
internal class AddPortChoice(private val bindable: HeadPortBindable, private val lastHeadPort: Int) {
    fun floor(current: Topology): Int {
        val occupied = current.heads.values.map { it.port } + daemonPorts(current)
        return maxOf(occupied.maxOrNull() ?: 0, FIRST_HEAD_PORT - 1) + 1
    }

    fun choose(current: Topology, candidateUrl: String?, floor: Int): Int? {
        val taken = current.heads.values.map { it.port }.toSet() + daemonPorts(current) +
            (current.providers.values.mapNotNull { localPort(it.baseUrl) } + listOfNotNull(localPort(candidateUrl)))
        return (floor..lastHeadPort).firstOrNull { port -> port !in taken && bindable.on(port) }
    }

    /** The daemon's own listeners: its control plane, and LAN share's port while sharing is on. */
    private fun daemonPorts(current: Topology): List<Int> =
        listOfNotNull(current.daemon.controlPort, current.share.port.takeIf { current.share.enabled })

    private fun localPort(baseUrl: String?): Int? {
        val uri = baseUrl?.let(::parsedUri) ?: return null
        val host = uri.host?.lowercase() ?: return null
        return uri.port.takeIf { it in 1..LAST_HEAD_PORT && loopbackHost(host) }
    }

    private fun parsedUri(baseUrl: String): URI? = try {
        URI(baseUrl)
    } catch (_: URISyntaxException) {
        null // the topology parser names an invalid URL after the port decision
    }

    private fun loopbackHost(host: String): Boolean =
        host == "localhost" || host == "localhost.localdomain" ||
            host == "::1" || host == "[::1]" || host.startsWith("127.")
}
