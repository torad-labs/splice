// NEW: (ledger lines 495, 519) owns startup preparation and pending-aware economics and request routes.
package splice.app.control.mount

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.usage.USAGE_READ_PENDING_HEADER
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeads
import splice.usage.economics.EconomicsPayloads
import splice.usage.perf.PerfRoutes

/** Keeps cold-read preparation and the management opt-in beside the readers they govern. */
internal class UsageReadRoutes(
    private val heads: UsageHeads,
    lookup: UsageHeadLookup,
    private val io: CoroutineDispatcher,
    private val guard: ControlGuard,
) {
    private val economics = EconomicsPayloads(heads, lookup = lookup)
    private val perf = PerfRoutes(lookup)

    fun register(route: Route) {
        val warmup = UsageReadWarmup(route.application, heads, io)
        perf.preparation = warmup
        route.application.monitor.subscribe(ApplicationStarted) { warmup.start() }
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
