package splice.app.control.perf

import kotlinx.serialization.json.JsonObject
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.util.JsonScalars

internal object PerfProfileInput {
    fun read(body: JsonObject): PerfProfileRequest? {
        val profiler = if ("profiler" in body) JsonScalars.str(body, "profiler") else "jfr"
        val mode = if ("mode" in body) JsonScalars.str(body, "mode") else "cpu"
        val duration = if ("durationSeconds" in body) {
            JsonScalars.str(
                body,
                "durationSeconds",
            )?.toIntOrNull()
        } else {
            DEFAULT_DURATION
        }
        val valid = listOf(
            body.keys.all { it in setOf("profiler", "mode", "durationSeconds") },
            duration != null && duration in 1..MAX_DURATION,
            profiler in setOf("jfr", "native"),
            mode in setOf("cpu", "wall", "alloc", "lock"),
        ).all { it }
        return if (valid) {
            PerfProfileRequest(
                requireNotNull(profiler),
                requireNotNull(mode),
                requireNotNull(duration),
            )
        } else {
            null
        }
    }
}

private const val DEFAULT_DURATION = 60
private const val MAX_DURATION = 300
