package splice.observability.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfIdentity

/** Pure wire projections; the sampler retains ownership of every cache and response reservation. */
internal class RuntimePayloads(private val identity: PerfIdentity) {
    fun sample(reading: RuntimeReading, owners: JsonArray, omitted: Int, sampledAt: Long): JsonObject {
        val failed = owners.count { RuntimeOwnerSampling.failed(it.jsonObject) }
        return buildJsonObject {
            put("schema_version", identity.schemaVersion)
            put("identity", Json.encodeToJsonElement(identity))
            put("as_of_ms", sampledAt)
            put("age_ms", 0)
            put("capabilities", reading.capabilities())
            put("measurements", reading.measurements())
            put("owner_readings", owners)
            put("owner_coverage", "registered_owners_only_partial")
            put("omitted_source_entries", omitted)
            put("failed_source_entries", failed)
            put(
                "issues",
                buildJsonArray {
                    if (omitted > 0) add(JsonPrimitive("runtime_owner_limit"))
                    if (failed > 0) add(JsonPrimitive("runtime_owner_read_failed"))
                },
            )
        }
    }

    fun unavailable(reason: String): JsonObject = buildJsonObject {
        put("schema_version", identity.schemaVersion)
        put("identity", Json.encodeToJsonElement(identity))
        put("as_of_ms", 0)
        put("age_ms", 0)
        put("capabilities", buildJsonArray {})
        put("measurements", buildJsonObject {})
        put("issues", buildJsonArray { add(JsonPrimitive(reason)) })
    }

    fun aged(sample: JsonObject, ageMs: Long): JsonObject = buildJsonObject {
        sample.forEach { (key, value) -> put(key, value) }
        put("age_ms", ageMs.coerceAtLeast(0L))
    }
}
