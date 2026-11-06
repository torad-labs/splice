package splice.app.control.perf

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.runInterruptible
import splice.app.observability.PerfTelemetry

/** The exporter lends an immutable cache snapshot with room for this single UTF-8 encoding. */
internal class PerfMetricsRoute(private val work: PerfHttpWork) {
    suspend fun respond(call: ApplicationCall, telemetry: PerfTelemetry?) {
        if (telemetry == null) {
            work.refusal(call, "unsupported", "performance_unavailable", HttpStatusCode.ServiceUnavailable)
            return
        }
        telemetry.exports.scrape().use { scrape ->
            val code = when {
                scrape.available -> HttpStatusCode.OK
                scrape.state == "disabled" -> HttpStatusCode.NotFound
                else -> HttpStatusCode.ServiceUnavailable
            }
            val bytes = runInterruptible(work.io) { scrape.text.toByteArray(Charsets.UTF_8) }
            call.respondBytes(bytes, PROMETHEUS_TYPE, code)
        }
    }
}

private val PROMETHEUS_TYPE = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
