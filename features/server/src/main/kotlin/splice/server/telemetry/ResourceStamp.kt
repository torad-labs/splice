// NEW: server mode (docs/specs/server-mode.md, F8). Who sent a piece of Claude Code's telemetry, written by the server on
// every resource of an OTLP export request. A client's own value for any of the stamp's keys is dropped, whatever it
// claimed, and the server's put in its place; every other attribute, every record and every byte the stamp does not
// own is kept as the client sent it. A request is stamped in the encoding it came in: OTLP/JSON here, protobuf in
// ProtoStamp.
package splice.server.telemetry

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The attributes a person's telemetry carries on every resource: [person] as `splice.person` and `user.email`, and
 *  the [team] they are on as `splice.team`, which a person on no team has none of. */
internal data class ResourceStamp(val person: String, val team: String?) {
    /** Every key the stamp owns, so a client's value for one is dropped even where the stamp writes none. */
    val owned: Set<String> get() = setOf(PERSON_KEY, EMAIL_KEY, TEAM_KEY)

    /** What the stamp writes, by key. */
    val written: List<Pair<String, String>> get() =
        listOfNotNull(PERSON_KEY to person, EMAIL_KEY to person, team?.let { TEAM_KEY to it })
}

/** The OTLP signals a relay takes, each by its path segment and the key its JSON request lists resources under. */
internal enum class OtlpSignal(val segment: String, val jsonResources: String) {
    LOGS("logs", "resourceLogs"),
    METRICS("metrics", "resourceMetrics"),
    TRACES("traces", "resourceSpans"),
}

/** Stamps an OTLP/JSON export request. */
internal class JsonStamp {
    /** [body], a JSON request of [signal], with [stamp] on each resource; null when it is no such request. */
    fun stamped(signal: OtlpSignal, body: ByteArray, stamp: ResourceStamp): ByteArray? {
        val root = parsed(body) ?: return null
        val resources = root[signal.jsonResources] ?: return body
        val entries = (resources as? JsonArray)?.takeIf { all -> all.all { it is JsonObject } }
        return entries?.let { listed ->
            val each = listed.map { stampedOne(it as JsonObject, stamp) }
            JsonObject(root + (signal.jsonResources to JsonArray(each))).toString().encodeToByteArray()
        }
    }

    private fun parsed(body: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(body.decodeToString()) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    /** One of a request's resource entries (a `ResourceLogs`, `ResourceMetrics` or `ResourceSpans`), stamped. */
    private fun stampedOne(entry: JsonObject, stamp: ResourceStamp): JsonObject {
        val resource = entry[RESOURCE_FIELD] as? JsonObject ?: JsonObject(emptyMap())
        val claimed = (resource[ATTRIBUTES] as? JsonArray).orEmpty()
        val kept = claimed.filterNot { keyOf(it) in stamp.owned }
        val written = stamp.written.map { (key, value) ->
            buildJsonObject {
                put("key", key)
                putJsonObject("value") { put("stringValue", value) }
            }
        }
        val stamped = JsonObject(resource + (ATTRIBUTES to JsonArray(kept + written)))
        return JsonObject(entry + (RESOURCE_FIELD to stamped))
    }

    private fun keyOf(attribute: JsonElement): String? =
        ((attribute as? JsonObject)?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private const val RESOURCE_FIELD = "resource"
private const val ATTRIBUTES = "attributes"

/** Who sent the telemetry, as the server knows them by their key. */
internal const val PERSON_KEY: String = "splice.person"

/** The person again, under the key Claude Code's own telemetry and most dashboards name a user by. */
internal const val EMAIL_KEY: String = "user.email"

/** The team the person is on. */
internal const val TEAM_KEY: String = "splice.team"
