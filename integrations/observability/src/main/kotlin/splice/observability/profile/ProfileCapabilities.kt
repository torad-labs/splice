package splice.observability.profile

import jdk.jfr.FlightRecorder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.runtime.PerfProfileRequest
import java.nio.file.Files
import java.nio.file.Path

internal class ProfileCapabilities(val nativeExecutable: Path?) {
    private val nativeSupported = mutableSetOf<String>()

    @Synchronized
    fun verified(mode: String) { nativeSupported.add(mode) }

    fun refusal(request: PerfProfileRequest): String? = when {
        request.durationSeconds !in 1..ProfileLimits.MAX_SECONDS -> "duration_must_be_1_to_300_seconds"
        request.mode !in ProfileLimits.modes -> "unknown_profile_mode"
        else -> profilerRefusal(request)
    }

    private fun profilerRefusal(request: PerfProfileRequest): String? = when (request.profiler) {
        JFR_PROFILER -> when {
            !FlightRecorder.isAvailable() -> "jfr_unavailable"
            request.mode == "wall" -> "jfr_wall_sampling_unavailable"
            else -> null
        }
        NATIVE_PROFILER -> if (nativeConfigured()) null else "native_profiler_not_configured_or_executable"
        else -> "unknown_profiler"
    }

    @Synchronized
    fun json() = buildJsonObject {
        put("schema_version", 1)
        put(
            "capabilities",
            buildJsonArray {
                for (profiler in listOf(JFR_PROFILER, NATIVE_PROFILER)) {
                    for (mode in ProfileLimits.modes) {
                        val reason = refusal(PerfProfileRequest(profiler, mode)) ?: probeReason(profiler, mode)
                        add(
                            buildJsonObject {
                                put("id", "$profiler.$mode")
                                put("supported", reason == null)
                                put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
                            },
                        )
                    }
                }
            },
        )
    }

    private fun nativeConfigured(): Boolean = nativeExecutable?.let {
        it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it)
    } == true

    private fun probeReason(profiler: String, mode: String): String? =
        if (profiler == NATIVE_PROFILER && mode !in nativeSupported) "mode_requires_explicit_start_probe" else null
}

private const val JFR_PROFILER = "jfr"
private const val NATIVE_PROFILER = "native"
