// NEW: LAYOUT-01 — the turns capability's routes: compaction state, the compaction instructions, and the
// opt-in body capture (features/turns).
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.TurnsHeadAdapter
import splice.app.control.api.HeadResolver
import splice.core.config.ConfigService
import splice.core.topology.TopologyWriterSource
import splice.head.compact.CompactPayloads
import splice.head.compaction.CompactionInstructionsRoute
import splice.head.wire.CaptureRoutes

/** Reads [ConsolePorts.compaction] and [ConsolePorts.topology] at CALL time: ControlPlane assigns them
 *  after the server is constructed, so a route that captured the value would capture null forever. */
internal class TurnsMount(
    heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    config: ConfigService,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val turnsLookup = TurnsHeadAdapter.lookup(resolver)
    private val compactPayloads = CompactPayloads(TurnsHeadAdapter.heads(heads))
    private val compactionRoute = CompactionInstructionsRoute(turnsLookup)
    private val captureRoutes = CaptureRoutes(turnsLookup, config, TopologyWriterSource { ports.topology })

    fun register(route: Route) {
        route.get("/api/compact") {
            guard.guarded(call) { ControlReplies.respond(call, compactPayloads.compactJson()) }
        }
        // V4-136: ?head=<key> is REQUIRED and an unknown one is a 400 naming it, never a 404 — the
        // console reads 404 on this path as route-not-built.
        route.get("/api/compaction/instructions") {
            guard.guarded(call) { compactionRoute.instructions(call, ports.compaction) }
        }
        // V4-133, FEATURES.md §5/§6: opt-in body capture (the TRACE knob, see CaptureRoutes' header).
        route.get("/api/heads/{head}/capture") {
            guard.guarded(call) { captureRoutes.read(call.parameters["head"].orEmpty()).send(call) }
        }
        route.put("/api/heads/{head}/capture") {
            guard.guarded(call) {
                captureRoutes.write(call.parameters["head"].orEmpty(), call.receiveText()).send(call)
            }
        }
    }
}
