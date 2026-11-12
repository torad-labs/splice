package splice.app.control.perf

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

internal object PerfReplyStatus {
    fun of(result: JsonObject): HttpStatusCode = when (JsonScalars.str(result, "status")) {
        "busy" -> HttpStatusCode.Conflict
        "error" -> HttpStatusCode.BadRequest
        "unsupported" -> HttpStatusCode.UnprocessableEntity
        "expired" -> HttpStatusCode.Gone
        else -> if (expired(result)) HttpStatusCode.Gone else HttpStatusCode.OK
    }

    private fun expired(result: JsonObject): Boolean {
        val metadata = result["metadata"] as? JsonObject
        val heads = result["heads"] as? JsonObject
        return listOfNotNull(metadata).plus(heads?.values.orEmpty().filterIsInstance<JsonObject>())
            .any { JsonScalars.str(it, "status") == "expired" }
    }
}
