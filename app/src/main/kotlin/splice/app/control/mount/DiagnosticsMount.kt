// NEW: LAYOUT-01 — the diagnostics capability's routes: the doctor report and the one never-recorded
// playground call (features/diagnostics), and the doctor fixes the console runs (V4-220 item 4).
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.PlaygroundHeadAdapter
import splice.app.control.api.ControlPayloads
import splice.app.control.api.HeadResolver
import splice.core.config.ConfigService
import splice.core.util.LogSink
import splice.daemonclient.DaemonProbe
import splice.diagnostics.doctor.DaemonAnswers
import splice.diagnostics.doctor.DaemonAnswersSource
import splice.diagnostics.doctor.DoctorRoute
import splice.diagnostics.playground.PlaygroundRoute
import splice.diagnostics.playground.PlaygroundSource

/** The daemon's own answers for its doctor, read in process (V4-230): the health payload, the heads roster,
 *  the auth body, each head's trace setting and tier gaps, and the account roster. The reads belong to the
 *  mounts that serve them, so this assembles them from those mounts and never asks over loopback from inside
 *  the request the daemon is serving. */
internal class DaemonSelfAnswers(
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    private val payloads: ControlPayloads,
    private val fleet: FleetMount,
    private val accounts: AccountsMount,
) : DaemonAnswersSource {
    override suspend fun invoke(): DaemonAnswers = DaemonAnswers(
        payloads.controlHealthJson(),
        fleet.headsJson(),
        accounts.authJson(),
        heads.mapValues { (key, _) -> DaemonProbe.HeadTrace(config.getConfig(key).trace) },
        unmappedTiers = heads.mapValues { (_, head) -> head.catalog?.unmappedTiers.orEmpty() },
        accounts = accounts.accountsJson(),
    )
}

/** [ports] is read at CALL time: ControlPlane assigns [ConsolePorts.doctor] and
 *  [ConsolePorts.playground] after the server is constructed. [answers] is the daemon's own /health,
 *  /api/heads and /api/auth, which its doctor reads in process (V4-230). */
internal class DiagnosticsMount(
    resolver: HeadResolver,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    log: LogSink,
    private val answers: DaemonAnswersSource,
) {
    private val doctorRoute = DoctorRoute(log)
    private val playgroundRoute =
        PlaygroundRoute(PlaygroundHeadAdapter.lookup(resolver), PlaygroundSource { ports.playground })

    fun register(route: Route) {
        route.get("/api/doctor") { guard.guarded(call) { doctorRoute.doctorJson(call, ports.doctor, answers) } }
        route.post("/api/doctor/fix/{id}") { guard.guarded(call) { doctorRoute.fix(call, ports.doctorFixes, answers) } }
        route.post("/api/playground") { guard.guarded(call) { playgroundRoute.run(call.receiveText()).send(call) } }
    }
}
