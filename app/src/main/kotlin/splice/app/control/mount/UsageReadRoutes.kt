// NEW: (ledger lines 495, 519) owns startup preparation and pending-aware economics and request routes.
package splice.app.control.mount

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splice.core.perf.KeptHistory
import splice.core.util.Cancellables
import splice.usage.USAGE_READ_PENDING_HEADER
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeads
import splice.usage.economics.EconomicsPayloads
import splice.usage.perf.PerfRoutes
import splice.usage.perf.RequestProjects

/** Keeps cold-read preparation and the management opt-in beside the readers they govern. */
internal class UsageReadRoutes(
    private val heads: UsageHeads,
    lookup: UsageHeadLookup,
    private val io: CoroutineDispatcher,
    private val guard: ControlGuard,
    kept: KeptHistory,
) {
    private val economics = EconomicsPayloads(heads, lookup = lookup, kept = kept)
    private val perf = PerfRoutes(lookup)

    /** Which project each request row belongs to, set once the sessions wiring exists. */
    fun tagProjects(tags: RequestProjects) {
        perf.projects = tags
    }

    fun register(route: Route) {
        val warmup = UsageReadWarmup(route.application, heads, io)
        perf.preparation = warmup
        route.application.monitor.subscribe(ApplicationStarted) {
            warmup.start()
            // the first project snapshot decodes every transcript head once; build it now, not on the first read
            route.application.launch(io) {
                Cancellables.discard(Cancellables.runCatchingBestEffort { perf.projects() }, TAGS_WARM_BEST_EFFORT)
            }
        }
        route.get("/api/perf/turns") {
            guard.guarded(call) { withContext(io) { perf.turns(call) } }
        }
        route.get("/api/economics") {
            guard.guarded(call) {
                val result = withContext(io) {
                    if (call.request.headers[USAGE_READ_PENDING_HEADER] == "1") {
                        economics.economicsJson(warmup)
                    } else {
                        economics.economicsJson()
                    }
                }
                ControlReplies.respond(call, result)
            }
        }
    }
}

// why: the first request that needs the project snapshot builds it again, so a failure here costs only the head start.
private const val TAGS_WARM_BEST_EFFORT = "the first project snapshot is rebuilt by the request that needs it"
