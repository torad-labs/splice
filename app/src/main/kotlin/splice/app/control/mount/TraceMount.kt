// NEW: V4-444 — the routes behind `splice trace` and `splice wire` (V4-239): a head's kept turns, the wire tap's
// ring, and the trace files the operator may count or delete. Split from TurnsMount, which keeps compaction,
// capture and the live turns.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.coroutines.withContext
import splice.app.control.ConsolePorts
import splice.app.control.TurnsHeadAdapter
import splice.app.control.api.HeadResolver
import splice.app.head.RetiredTurnsHeads
import splice.core.config.ConfigService
import splice.core.retired.RetiredHeads
import splice.head.trace.TraceDirPort
import splice.head.trace.TraceDocumentQuery
import splice.head.trace.TraceRoute
import splice.head.trace.TraceSearchQuery
import splice.head.wire.TraceDeleteRoutes
import splice.head.wire.WireRoutes
import splice.head.wire.WireTapsSource
import splice.upstream.codemode.ProcessDispatchers

/** Reads [ConsolePorts.traceDir] and [ConsolePorts.wires] at CALL time: ControlPlane assigns them after the server
 *  is constructed, so a route that captured the value would capture null forever. */
internal class TraceMount(
    resolver: HeadResolver,
    config: ConfigService,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val turnsLookup = TurnsHeadAdapter.lookup(resolver)
    private val fileIo = ProcessDispatchers().io()
    private val traceRoute = TraceRoute(
        RetiredTurnsHeads(RetiredHeads(config.statePaths.stateDir), turnsLookup),
        TraceDirPort { ports.traceDir },
        fileIo,
    )
    private val traceDeleteRoutes = TraceDeleteRoutes(turnsLookup, TraceDirPort { ports.traceDir }, config)
    private val wireRoutes = WireRoutes(turnsLookup, WireTapsSource { ports.wires })

    fun register(route: Route) {
        registerKeptTrace(route)
        // V4-239: the verbs' reads, the trace's files and the wire tap's ring, under the same key.
        route.get("/api/heads/{head}/trace") {
            guard.guarded(call) {
                val query = call.request.queryParameters
                val head = call.parameters["head"].orEmpty()
                traceRoute.read(head, query["turn"], outline = query["view"] == "outline").send(call)
            }
        }
        route.get("/api/heads/{head}/trace/document") {
            guard.guarded(call) {
                val query = call.request.queryParameters
                val asked = TraceDocumentQuery(
                    query["turn"],
                    query["record"],
                    query["section"],
                    query["path"],
                    query["offset"],
                )
                traceRoute.read(call.parameters["head"].orEmpty(), asked.turn, document = asked).send(call)
            }
        }
        route.get("/api/heads/{head}/trace/search") {
            guard.guarded(call) {
                val query = call.request.queryParameters
                val asked = TraceSearchQuery(q = query["q"], since = query["since"], limit = query["limit"])
                traceRoute.search(call.parameters["head"].orEmpty(), asked).send(call)
            }
        }
        route.get("/api/heads/{head}/wire") {
            guard.guarded(call) {
                wireRoutes.read(call.parameters["head"].orEmpty(), call.request.queryParameters["last"]).send(call)
            }
        }
    }

    /** The configured head names the only trace prefix these reads may count or delete. */
    private fun registerKeptTrace(route: Route) {
        route.get("/api/heads/{head}/trace/kept") {
            guard.guarded(call) {
                withContext(fileIo) { traceDeleteRoutes.kept(call.parameters["head"].orEmpty()) }.send(call)
            }
        }
        route.delete("/api/heads/{head}/trace/kept") {
            guard.guarded(call) {
                withContext(fileIo) { traceDeleteRoutes.delete(call.parameters["head"].orEmpty()) }.send(call)
            }
        }
    }
}
