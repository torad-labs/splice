package splice.usage.perf.query

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.query.PerfQueryJson.queryError

/** Used inside the host's existing authenticated loopback routes and blocking query executor. */
public object PerfQueryHttp {
    public suspend fun respond(call: ApplicationCall, service: PerfQueryService, operation: String, nowMs: Long) {
        val entries = call.request.queryParameters.entries()
        val result = if (entries.any { it.value.size != 1 }) {
            queryError("invalid_query", "duplicate_parameter")
        } else {
            evaluate(entries.associate { it.key to it.value.first() }, service, operation, nowMs)
        }
        val status = when (JsonScalars.str(result, "status")) {
            "error" -> HttpStatusCode.BadRequest
            "busy" -> HttpStatusCode.ServiceUnavailable
            "unsupported" -> HttpStatusCode.UnprocessableEntity
            else -> HttpStatusCode.OK
        }
        val encoded = result.toString()
        PerfWorkBudget.release(result)
        call.respondText(encoded, ContentType.Application.Json, status)
    }

    private fun evaluate(
        parameters: Map<String, String>,
        service: PerfQueryService,
        operation: String,
        nowMs: Long,
    ): JsonObject {
        val parsed = PerfQueryParser.parse(parameters, nowMs)
        val query = parsed.query ?: return requireNotNull(parsed.error)
        return dispatch(query, service, operation, parameters)
    }

    private fun dispatch(
        query: PerfQuery,
        service: PerfQueryService,
        operation: String,
        parameters: Map<String, String>,
    ): JsonObject {
        val size = parameters["page_size"]?.toIntOrNull() ?: DEFAULT_ROWS
        return when (operation) {
            "summary" -> service.summary(query)
            "series" -> service.series(query)
            "diagnose" -> service.diagnose(query)
            "schema" -> service.schema()
            "rows" -> service.rows(query, parameters["cursor"], size)
            "observations" -> service.observations(query, parameters["cursor"], size)
            else -> queryError("unknown_operation", "unsupported_query_operation")
        }
    }
}

private const val DEFAULT_ROWS = 200
