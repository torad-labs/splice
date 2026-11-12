package splice.observability.runtime

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed metric keys only; unsupported readings carry a reason instead of a fabricated zero. */
internal class RuntimeReading {
    val values = linkedMapOf<String, Double?>()
    private val reasons = linkedMapOf<String, String>()

    fun add(id: String, value: Number?, reason: String = "counter unavailable on this JVM or operating system") {
        values[id] = value?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        if (values[id] == null) reasons[id] = reason else reasons.remove(id)
    }

    fun merge(source: JsonObject): JsonObject {
        val owner = RuntimeReading()
        val readings = source["measurements"] as? JsonObject ?: JsonObject(emptyMap())
        readings.entries.take(MAX_OWNER_METRICS).forEach { (id, value) ->
            if (id.startsWith("runtime_") && splice.core.perf.observation.PerfMetricRegistry.permits(
                    id,
                    splice.core.perf.observation.PerfProjection.LOCAL,
                )
            ) {
                val numeric = (value as? JsonPrimitive)?.content?.toDoubleOrNull()
                owner.add(id, numeric, "owner did not expose this counter")
                mergeValue(id, owner.values[id])
            }
        }
        return buildJsonObject {
            put("measurements", owner.measurements())
            put("capabilities", owner.capabilities())
        }
    }

    private fun mergeValue(id: String, value: Double?) {
        val combined = when {
            !values.containsKey(id) -> value
            value == null || values[id] == null -> null
            summable(id) -> checkNotNull(values[id]) + value
            id == "runtime_tool_oldest_reply_ms" -> maxOf(checkNotNull(values[id]), value)
            else -> null
        }
        add(id, combined, "counter unavailable or overlapping owners prevent a process total")
    }

    fun measurements(): JsonObject = JsonObject(
        values.mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull },
    )

    private fun summable(id: String): Boolean = id.startsWith("runtime_http_") || id in setOf(
        "runtime_tools_active",
        "runtime_tool_capacity",
        "runtime_tool_queued",
        "runtime_tool_pending_replies",
        "runtime_processes_active",
    )

    fun capabilities() = buildJsonArray {
        values.forEach { (id, value) ->
            add(
                buildJsonObject {
                    put("id", id)
                    put("supported", value != null)
                    put("reason", reasons[id]?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }
    }
}

// Read at most 64 metrics from each owner so one source cannot expand the cached runtime projection indefinitely.
private const val MAX_OWNER_METRICS = 64
