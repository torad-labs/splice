package splice.observability.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileRequest
import java.util.UUID

internal class ProfileRecord(val request: PerfProfileRequest, val fromMs: Long) {
    val id: String = UUID.randomUUID().toString()
    var status = "running"
    var untilMs: Long? = null
    var sizeBytes = 0L
    var available = false
    var issue: String? = null

    fun reply(identity: PerfIdentity, nowMs: Long, status: String, issue: String): JsonObject {
        this.status = status
        this.issue = issue
        untilMs = nowMs
        return json(identity)
    }

    fun json(identity: PerfIdentity, nowMs: Long = fromMs) = buildJsonObject {
        val expired = untilMs?.let { nowMs - it > ProfileLimits.RETENTION_MS } == true
        put("schema_version", identity.schemaVersion)
        put("status", status)
        put("profile_id", id)
        put("identity", Json.encodeToJsonElement(identity))
        put("process_id", ProcessHandle.current().pid())
        put("from_ms", fromMs)
        put("until_ms", untilMs?.let(::JsonPrimitive) ?: JsonNull)
        put("window_scope", "capture_operation_including_setup_and_finalization")
        put(
            "settings",
            buildJsonObject {
                put("profiler", request.profiler)
                put("mode", request.mode)
                put("duration_seconds", request.durationSeconds)
                put("artifact_limit_bytes", ProfileLimits.ARTIFACT_BYTES)
            },
        )
        put(
            "limitations",
            buildJsonArray {
                add(JsonPrimitive("Process samples cannot attribute every sample to a request"))
                add(
                    JsonPrimitive(
                        "Chunk retention may truncate the requested window; " +
                            "final artifact bytes are checked separately",
                    ),
                )
                add(
                    JsonPrimitive(
                        "Only stack, allocation-size and contention events; " +
                            "no request bodies, environment or socket payloads",
                    ),
                )
            },
        )
        put("size_bytes", sizeBytes)
        put("artifact_available", available && !expired)
        put(
            "issues",
            buildJsonArray {
                issue?.let { add(JsonPrimitive(it)) }
                if (expired) add(JsonPrimitive("artifact_expired"))
            },
        )
    }
}
