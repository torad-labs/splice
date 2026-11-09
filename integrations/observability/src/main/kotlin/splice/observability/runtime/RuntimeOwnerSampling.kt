package splice.observability.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.Cancellables

/** Failure retains only the previous bounded metric keys and success timestamp, never stale numeric readings. */
internal object RuntimeOwnerSampling {
    fun sample(
        source: PerfRuntimeSource,
        index: Int,
        previous: JsonObject?,
        reading: RuntimeReading,
        nowMs: Long,
    ): JsonObject {
        var failed = false
        val data = Cancellables.runCatchingBestEffort { source.runtime() }.getOrElse { error ->
            propagateTermination(error)
            failed = true
            unknown(previous)
        }
        val merged = reading.merge(data)
        return buildJsonObject {
            put("owner_index", index)
            put("status", if (failed) "failed" else "ok")
            put("sampled_at_ms", nowMs)
            put("last_success_ms", if (failed) previous?.get("last_success_ms") ?: JsonNull else JsonPrimitive(nowMs))
            put("issues", buildJsonArray { if (failed) add(JsonPrimitive("runtime_owner_read_failed")) })
            merged.forEach { (key, value) -> put(key, value) }
        }
    }

    fun failed(sample: JsonObject): Boolean = sample["status"] == JsonPrimitive("failed")

    private fun unknown(previous: JsonObject?): JsonObject = buildJsonObject {
        val known = previous?.get("measurements") as? JsonObject
        put("measurements", JsonObject(known?.mapValues { JsonNull }.orEmpty()))
    }

    private fun propagateTermination(error: Throwable) {
        val ordinary = error is Exception && error !is CancellationException
        if (ordinary && error !is InterruptedException) return
        if (error is InterruptedException) Thread.currentThread().interrupt()
        throw error
    }
}
