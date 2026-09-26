// NEW: V4-220 item 3 (2026-09-25) — `splice add` on the control plane (features/configuration's
// AddRoutes) and `splice add-model` (AddModelRoutes), every route behind the management door. The
// restart after either write is the console button's own: the SAME DaemonRestarts LifecycleMount
// builds, so a compaction in flight is waited for and an unsupervised daemon is not drained.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ConsolePorts
import splice.configuration.add.AddConsoleSource
import splice.configuration.add.AddDaemonRestart
import splice.configuration.add.AddModelRoutes
import splice.configuration.add.AddRestartTaken
import splice.configuration.add.AddRoutes
import splice.configuration.add.AddWaitingCompaction
import splice.core.util.LogSink
import splice.lifecycle.restart.DaemonRestarts
import splice.lifecycle.restart.RestartPhase
import splice.lifecycle.restart.RestartTaken
import splice.lifecycle.restart.ShutdownDaemon

/** [ports] is read at CALL time: ConsoleWiring assigns [ConsolePorts.add] and [ConsolePorts.supervised]
 *  after the server is constructed. */
internal class AddMount(
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    restarts: DaemonRestarts,
    shutdown: ShutdownDaemon,
    log: LogSink,
) {
    private val restart = SaveRestart(restarts, shutdown, ports)
    private val routes = AddRoutes(AddConsoleSource { ports.add }, restart, log)
    private val models = AddModelRoutes(AddConsoleSource { ports.add }, restart, log)

    fun register(route: Route) {
        route.get("/api/add/profiles") { guard.guarded(call) { routes.profiles(call) } }
        route.post("/api/add") { guard.guarded(call) { routes.open(call) } }
        route.get("/api/add/{id}") { guard.guarded(call) { routes.poll(call) } }
        route.post("/api/add/{id}/login") { guard.guarded(call) { routes.signIn(call) } }
        route.post("/api/add/{id}/verify") { guard.guarded(call) { routes.verify(call) } }
        route.post("/api/add/{id}/save") { guard.guarded(call) { routes.save(call) } }
        route.delete("/api/add/{id}") { guard.guarded(call) { routes.discard(call) } }
        route.get("/api/add-model") { guard.guarded(call) { models.list(call) } }
        route.post("/api/add-model") { guard.guarded(call) { models.add(call) } }
    }
}

/** The save's restart, taken exactly as POST /api/daemon/restart takes one without `now`. */
private class SaveRestart(
    private val restarts: DaemonRestarts,
    private val shutdown: ShutdownDaemon,
    private val ports: ConsolePorts,
) : AddDaemonRestart {
    override fun take(): AddRestartTaken = when (val taken = restarts.take(false, shutdown, ports.supervised)) {
        is RestartTaken.Refused -> AddRestartTaken.Refused(taken.reason)
        is RestartTaken.Accepted -> when (val phase = taken.phase) {
            RestartPhase.Draining -> AddRestartTaken.Draining
            is RestartPhase.Waiting ->
                AddRestartTaken.Waiting(phase.compactions.map { AddWaitingCompaction(it.head, it.ageMs) })
            // request() answers Draining or Waiting; Idle would be a restart nobody took on, said so.
            RestartPhase.Idle -> AddRestartTaken.Refused("the daemon did not take the restart on")
        }
    }

    override fun drain() {
        shutdown()
    }
}
