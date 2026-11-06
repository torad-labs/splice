package splice.app.control.perf

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import splice.app.control.ConsolePorts
import splice.app.control.mount.ControlGuard
import splice.usage.perf.query.PerfQueryParser

internal class PerfSettingsRoutes(
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    private val work: PerfHttpWork,
) {
    fun register(route: Route) {
        route.get("/api/perf/objectives") {
            guard.guarded(call) {
                work.execute(call) {
                    work.respond(call, ports.performance) {
                        ports.performance?.objectives?.settings() ?: work.unavailable()
                    }
                }
            }
        }
        route.put("/api/perf/objectives") {
            guard.guarded(call) {
                work.execute(call) {
                    PerfBody.consume(call, work) { body ->
                        work.respond(call, ports.performance) {
                            ports.performance?.objectives?.update(body) ?: work.unavailable()
                        }
                    }
                }
            }
        }
        route.get("/api/perf/alerts/status") {
            guard.guarded(call) {
                work.execute(call) {
                    work.respond(call, ports.performance) {
                        ports.performance?.alertEngine?.status() ?: work.unavailable()
                    }
                }
            }
        }
        route.get("/api/perf/alerts") {
            guard.guarded(call) { work.execute(call) { history(call) } }
        }
    }

    private suspend fun history(call: ApplicationCall) {
        val parameters = call.request.queryParameters.entries()
        work.respond(call, ports.performance) {
            val valid = parameters.all { it.key in setOf("from", "until") && it.value.size == 1 }
            val parsed = PerfQueryParser.parse(
                parameters.associate { it.key to it.value.first() },
                System.currentTimeMillis(),
            )
            val query = parsed.query
            when {
                !valid -> work.error("error", "invalid_alert_parameters")
                query == null -> requireNotNull(parsed.error)
                else -> ports.performance?.alertEngine?.history(query.fromMs, query.untilMs) ?: work.unavailable()
            }
        }
    }
}
