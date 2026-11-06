package splice.app.control.perf

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import splice.app.control.ConsolePorts
import splice.app.control.mount.ControlGuard

/** Mounted on the existing loopback control plane and always inside its management bearer guard. */
internal class PerfMount(
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    val work: PerfHttpWork = PerfHttpWork(),
) {
    val queries = PerfQueryRoutes(work)
    private val profiles = PerfProfileRoutes(ports, guard, work)
    private val settings = PerfSettingsRoutes(ports, guard, work)
    private val metrics = PerfMetricsRoute(work)

    suspend fun selected(call: ApplicationCall, kind: String): Boolean = queries.selected(call, ports.performance, kind)

    fun register(route: Route) {
        listOf("schema", "series", "diagnose", "observations").forEach { operation ->
            route.get("/api/perf/$operation") {
                guard.guarded(call) { work.execute(call) { queries.respond(call, ports.performance, operation) } }
            }
        }
        route.get("/api/perf/runtime") {
            guard.guarded(call) {
                work.execute(call) {
                    work.respond(call, ports.performance) {
                        ports.performance?.runtime?.runtime() ?: work.unavailable()
                    }
                }
            }
        }
        route.get("/api/perf/export/status") {
            guard.guarded(call) {
                work.execute(call) {
                    work.respond(call, ports.performance) {
                        ports.performance?.exports?.status() ?: work.unavailable()
                    }
                }
            }
        }
        route.get("/api/perf/instrumentation/status") {
            guard.guarded(call) {
                work.execute(call) {
                    work.respond(call, null) {
                        ports.performance?.instrumentation?.status() ?: work.unavailable()
                    }
                }
            }
        }
        route.get("/api/perf/metrics") {
            guard.guarded(call) { work.execute(call) { metrics.respond(call, ports.performance) } }
        }
        profiles.register(route)
        settings.register(route)
    }
}
