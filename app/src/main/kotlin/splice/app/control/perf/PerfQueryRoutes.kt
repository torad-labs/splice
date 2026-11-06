package splice.app.control.perf

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import splice.app.observability.PerfTelemetry
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryHttp
import splice.usage.perf.query.PerfQueryParser
import splice.usage.perf.query.PerfQueryService

/** Selection validation and computation use the feature's parser/service; this adapter owns HTTP response lifetime. */
internal class PerfQueryRoutes(private val work: PerfHttpWork) {
    suspend fun selected(call: ApplicationCall, telemetry: PerfTelemetry?, kind: String): Boolean {
        if (call.request.queryParameters["contract"] != "1") {
            return PerfQueryHttp.selected(call, telemetry?.query, kind, System.currentTimeMillis())
        }
        respond(call, telemetry, kind)
        return true
    }

    suspend fun respond(call: ApplicationCall, telemetry: PerfTelemetry?, kind: String) {
        work.respond(call, telemetry) read@{
            if (telemetry == null) return@read work.unavailable()
            val entries = call.request.queryParameters.entries()
            if (entries.any { it.value.size != 1 }) return@read work.error("error", "duplicate_parameter")
            val parameters = entries.associate { it.key to it.value.first() }
            val parsed = PerfQueryParser.parse(parameters, System.currentTimeMillis())
            val query = parsed.query ?: return@read requireNotNull(parsed.error)
            dispatch(telemetry.query, kind, query, parameters)
        }
    }

    private fun dispatch(
        service: PerfQueryService,
        kind: String,
        query: PerfQuery,
        parameters: Map<String, String>,
    ): JsonObject {
        val pageSize = parameters["page_size"]?.toIntOrNull() ?: DEFAULT_ROWS
        return when (kind) {
            "schema" -> service.schema()
            "series" -> service.series(query)
            "diagnose" -> service.diagnose(query)
            "summary" -> service.summary(query)
            "rows" -> service.rows(
                query,
                parameters["cursor"],
                pageSize,
            )
            "observations" -> service.observations(
                query,
                parameters["cursor"],
                pageSize,
            )
            else -> work.error("error", "unsupported_operation")
        }
    }
}

private const val DEFAULT_ROWS = 200
