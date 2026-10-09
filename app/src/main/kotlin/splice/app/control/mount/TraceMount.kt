// NEW: V4-444 — the routes behind `splice trace` and `splice wire` (V4-239): a head's kept turns, the conversation a
// turn carried, the wire tap's ring, and the trace files the operator may count or delete. Split from TurnsMount,
// which keeps compaction, capture and the live turns.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.coroutines.withContext
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.SessionHeadAdapter
import splice.app.control.TurnsHeadAdapter
import splice.app.control.api.HeadResolver
import splice.client.transcript.TranscriptMessageLookup
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.core.turn.FailureCause
import splice.head.trace.TraceDirPort
import splice.head.trace.TraceFailureCause
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.trace.TranscriptRequestRoute
import splice.head.trace.TranscriptRoots
import splice.head.wire.TraceDeleteRoutes
import splice.head.wire.WireRoutes
import splice.head.wire.WireTapsSource
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.upstream.codemode.ProcessDispatchers

/** Reads [ConsolePorts.traceDir] and [ConsolePorts.wires] at CALL time: ControlPlane assigns them after the server
 *  is constructed, so a route that captured the value would capture null forever. */
internal class TraceMount(
    heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    config: ConfigService,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val turnsLookup = TurnsHeadAdapter.lookup(resolver)
    private val fileIo = ProcessDispatchers().io()
    private val traceRoute = TraceRoute(
        turnsLookup,
        TraceDirPort { ports.traceDir },
        fileIo,
        TraceFailureCause { key, turn, since ->
            val row = heads[key]?.perfRows?.window(since)?.rows?.lastOrNull { it.turn == turn }
            row?.cause?.let { recorded -> FailureCause.entries.firstOrNull { it.name == recorded } }
        },
    )
    private val traceDeleteRoutes = TraceDeleteRoutes(turnsLookup, TraceDirPort { ports.traceDir }, config)
    private val sessionHeads = SessionHeadAdapter.adapt(heads)
    private val transcriptRoute = TranscriptRequestRoute(
        TranscriptMessageLookup(),
        TranscriptRoots { key ->
            if (key !in heads) {
                null
            } else {
                // The same priority SessionsRoutes uses: this head's tree, vanilla Claude Code,
                // then every other head's own tree. The request supplies neither a path nor a root.
                val own = sessionHeads[key]?.transcriptRoot
                val others = sessionHeads.values.mapNotNull { it.transcriptRoot }.filter { it != own }
                (listOfNotNull(own) + listOf(UserHome.claudeDir()) + others).distinct()
            }
        },
        SessionTranscriptViewEnabled { config.getConfig().transcriptView },
        fileIo,
    )
    private val wireRoutes = WireRoutes(turnsLookup, WireTapsSource { ports.wires })

    fun register(route: Route) {
        registerKeptTrace(route)
        // V4-239: the verbs' reads, the trace's files and the wire tap's ring, under the same key.
        route.get("/api/heads/{head}/trace") {
            guard.guarded(call) {
                val query = call.request.queryParameters
                val asked = TraceQuery(last = query["last"], session = query["session"], turn = query["turn"])
                traceRoute.read(call.parameters["head"].orEmpty(), asked).send(call)
            }
        }
        route.get("/api/heads/{head}/conversation") {
            guard.guarded(call) {
                val ask = call.request.queryParameters
                transcriptRoute.read(
                    call.parameters["head"].orEmpty(),
                    ask["session"],
                    ask["message"],
                ).send(call)
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
