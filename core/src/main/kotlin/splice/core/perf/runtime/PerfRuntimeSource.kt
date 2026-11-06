package splice.core.perf.runtime

import kotlinx.serialization.json.JsonObject

/** Reads the most recent sample without recording telemetry or starting work. */
fun interface PerfRuntimeSource {
    fun runtime(): JsonObject
}
