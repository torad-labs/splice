package splice.core.perf.runtime

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

data class PerfProfileRequest(
    val profiler: String = "jfr",
    val mode: String = "cpu",
    val durationSeconds: Int = 60,
)

data class PerfProfileArtifact(
    val id: String,
    val path: Path,
    val sizeBytes: Long,
    val mediaType: String,
)

/** Bounded, explicit local captures; returned artifacts are validated managed files. */
interface PerfProfiles {
    fun capabilities(): JsonObject

    fun start(request: PerfProfileRequest): JsonObject

    fun stop(id: String): JsonObject

    fun status(id: String? = null): JsonObject

    fun artifact(id: String): PerfProfileArtifact?
}
