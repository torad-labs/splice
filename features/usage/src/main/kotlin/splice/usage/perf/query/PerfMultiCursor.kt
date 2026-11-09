package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.usage.perf.query.PerfQueryJson.queryJson
import splice.usage.perf.query.PerfQueryJson.textValue
import java.security.MessageDigest
import java.util.Base64

internal object PerfMultiCursor {
    fun hash(query: PerfQuery): String = MessageDigest.getInstance("SHA-256")
        .digest(queryJson(query.copy(filters = query.filters.toSortedMap())).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun encode(query: PerfQuery, cursors: Map<String, HeadCursor>): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(
            buildJsonObject {
                put("query_hash", hash(query))
                put(
                    "heads",
                    JsonObject(
                        cursors.mapValues { (_, state) ->
                            buildJsonObject {
                                put("start", textValue(state.start))
                                put("next", textValue(state.next))
                            }
                        },
                    ),
                )
            }.toString().toByteArray(Charsets.UTF_8),
        )

    fun decode(token: String, query: PerfQuery, heads: List<String>): Map<String, HeadCursor>? {
        if (token.length > MAX_CURSOR_CHARS) return null
        return try {
            val decoded = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
            val json = requireNotNull(JsonScalars.objectOrNull(Json, decoded))
            require(JsonScalars.str(json, "query_hash") == hash(query))
            val values = requireNotNull(json["heads"] as? JsonObject)
            require(values.keys == heads.toSet())
            values.mapValues { (_, value) ->
                val state = requireNotNull(value as? JsonObject)
                HeadCursor(requireNotNull(JsonScalars.str(state, "start")), JsonScalars.str(state, "next"))
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
