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
            PerfProfileRequest().durationSeconds
        }
        val valid = listOf(
            body.keys.all { it in setOf("profiler", "mode", "durationSeconds") },
            duration != null && duration in 1..MAX_HTTP_PROFILE_SECONDS,
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

// The HTTP capture contract permits at most five minutes, matching the capture manager's admission bound.
private const val MAX_HTTP_PROFILE_SECONDS = 300
