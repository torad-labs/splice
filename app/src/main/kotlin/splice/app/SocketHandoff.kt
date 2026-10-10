// NEW: the daemon's reading of a socket manager's hand-off. Under systemd socket activation the manager holds splice's
// listening ports, so a client that connects while the daemon restarts waits in the kernel's accept queue and is
// answered by the next daemon instead of being refused. This turns LISTEN_PID / LISTEN_FDS / LISTEN_FDNAMES into the
// listeners the control plane and each head serve on, or into a refusal listing every reason before anything binds.
package splice.app

import splice.app.control.ControlListen
import splice.core.listen.InheritedPlan
import splice.core.listen.InheritedSockets
import splice.core.listen.ListenerNames
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.http.listen.AdoptedBootstrap
import splice.http.listen.AdoptedListeners
import splice.http.listen.Adoption
import splice.http.listen.DescriptorAdoption

internal sealed class Handoff {
    /** Nothing was handed over: every listener binds its own port, exactly as without a manager. */
    data object Nothing : Handoff()

    data class Adopted(val listeners: AdoptedListeners, val summary: String) : Handoff()

    data class Refused(val reasons: List<String>) : Handoff()
}

internal class SocketHandoff(
    private val env: EnvReader,
    private val pid: Long = ProcessHandle.current().pid(),
    private val adoption: DescriptorAdoption = DescriptorAdoption(),
) {
    private val names = ListenerNames()

    fun resolve(topology: Topology, controlPort: Int): Handoff {
        val ports = linkedMapOf(names.control to controlPort)
        topology.heads.forEach { (key, head) -> ports[names.head(key)] = head.port }
        return when (val plan = InheritedSockets(pid).plan(env, ports.keys)) {
            InheritedPlan.None -> Handoff.Nothing
            is InheritedPlan.Refuse -> Handoff.Refused(plan.reasons)
            is InheritedPlan.Adopt -> when (val result = adoption.adopt(plan.fds, ports)) {
                is Adoption.Refused -> Handoff.Refused(result.reasons)
                is Adoption.Ready -> Handoff.Adopted(
                    result.listeners,
                    "socket-activation: inherited=${plan.fds.size} self-bound=${ports.size - plan.fds.size}",
                )
            }
        }
    }
}

/**
 * Which listener serves on which handed-over socket, and the one place in the daemon that knows adoption exists: the
 * control plane, the head factory and Daemon itself read their listener from here and never name a socket type, so
 * none of them gains a dependency on the listen packages (concentration, 2026-10-10, splice-lead). Built with no
 * listeners — the default — every listener binds its own port, which is every start without a socket manager.
 */
internal class AdoptedServing(private val listeners: AdoptedListeners? = null) {
    private val names = ListenerNames()

    /** The control port, with the socket a manager already holds for it when the hand-off named one. */
    fun control(port: Int): ControlListen = ControlListen(port, socketFor(names.control))

    /** Head [key]'s socket, or null when the hand-off named none and the head binds its own port. */
    fun head(key: String): AdoptedBootstrap? = socketFor(names.head(key))

    private fun socketFor(name: String): AdoptedBootstrap? =
        listeners?.takeIf { it.has(name) }?.bootstrapFor(name)
}
