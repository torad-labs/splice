package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.util.Base64

internal object PerfObservationCursor {
    fun encode(cursor: String, query: PerfQuery): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        buildJsonObject { put("cursor", cursor); put("query_hash", PerfMultiCursor.hash(query)) }
            .toString().toByteArray(Charsets.UTF_8),
    )

    fun decode(cursor: String, query: PerfQuery): String? {
        if (cursor.length > MAX_CURSOR_CHARS) return null
        return try {
            val decoded = Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8)
            val value = JsonScalars.objectOrNull(Json, decoded)
            JsonScalars.str(
                value,
                "cursor",
            ).takeIf { JsonScalars.str(value, "query_hash") == PerfMultiCursor.hash(query) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
