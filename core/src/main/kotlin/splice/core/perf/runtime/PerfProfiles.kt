package splice.core.perf.runtime

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

public data class PerfProfileRequest(
    public val profiler: String = "jfr",
    public val mode: String = "cpu",
    public val durationSeconds: Int = 60,
)

public data class PerfProfileArtifact(
    public val id: String,
    public val path: Path,
    public val sizeBytes: Long,
    public val mediaType: String,
)

/** Bounded, explicit local captures; returned artifacts are validated managed files. */
public interface PerfProfiles {
    public fun capabilities(): JsonObject

    public fun start(request: PerfProfileRequest): JsonObject

    public fun stop(id: String): JsonObject

    public fun status(id: String? = null): JsonObject

    public fun artifact(id: String): PerfProfileArtifact?

    /** Opens a bounded reader that keeps its artifact retained until the caller closes it. */
    public fun openArtifact(id: String): PerfProfileRead? = null
}
