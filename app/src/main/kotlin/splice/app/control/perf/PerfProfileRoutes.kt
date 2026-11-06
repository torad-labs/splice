package splice.app.control.perf

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.withContext
import splice.app.control.ConsolePorts
import splice.app.control.mount.ControlGuard

internal class PerfProfileRoutes(
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    private val work: PerfHttpWork,
) {
    fun register(route: Route) {
        route.get("/api/perf/profiles/capabilities") {
            guard.guarded(call) {
                work.execute(call) {
                    work.respond(call, ports.performance) {
                        ports.performance?.profiles?.capabilities() ?: work.unavailable()
                    }
                }
            }
        }
        route.get("/api/perf/profiles") {
            guard.guarded(call) { work.execute(call) { status(call, null) } }
        }
        route.post("/api/perf/profiles") {
            guard.guarded(call) { work.execute(call) { start(call) } }
        }
        route.get("/api/perf/profiles/{id}") {
            guard.guarded(call) { work.execute(call) { validId(call)?.let { status(call, it) } } }
        }
        route.post("/api/perf/profiles/{id}/stop") {
            guard.guarded(call) {
                work.execute(call) {
                    validId(call)?.let { id ->
                        work.respond(call, ports.performance) {
                            ports.performance?.profiles?.stop(id) ?: work.unavailable()
                        }
                    }
                }
            }
        }
        route.get("/api/perf/profiles/{id}/artifact") {
            guard.guarded(call) { work.execute(call) { validId(call)?.let { artifact(call, it) } } }
        }
    }

    private suspend fun status(call: ApplicationCall, id: String?) = work.respond(call, ports.performance) {
        ports.performance?.profiles?.status(id) ?: work.unavailable()
    }

    private suspend fun start(call: ApplicationCall) = PerfBody.consume(call, work) { body ->
        val request = PerfProfileInput.read(body)
        work.respond(call, ports.performance) {
            if (request == null) {
                work.error("error", "invalid_profile_request")
            } else {
                ports.performance?.profiles?.start(request) ?: work.unavailable()
            }
        }
    }

    private suspend fun validId(call: ApplicationCall): String? {
        val id = call.parameters["id"]
        if (id != null && PROFILE_ID.matches(id)) return id
        work.refusal(call, "error", "invalid_profile_id", HttpStatusCode.BadRequest)
        return null
    }

    private suspend fun artifact(call: ApplicationCall, id: String) = withContext(work.io) {
        val profiles = ports.performance?.profiles
        val reader = profiles?.openArtifact(id)
        if (reader == null) {
            if (profiles?.artifact(id) != null) {
                work.refusal(call, "busy", "artifact_reader_capacity", HttpStatusCode.TooManyRequests)
            } else {
                work.refusal(call, "expired", "profile_artifact_unavailable", HttpStatusCode.Gone)
            }
        } else {
            PerfProfileContent(reader, work.io).use { content ->
                call.respond(content)
                content.awaitDelivered()
            }
        }
    }
}

private val PROFILE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
