package splice.core.perf.runtime

/** A single admitted download. Close after delivery, including failed or cancelled delivery. */
public interface PerfProfileRead : AutoCloseable {
    public val artifact: PerfProfileArtifact

    /** Reads at most [PERF_PROFILE_READ_BUFFER_BYTES] into the caller's reusable buffer. */
    public fun read(buffer: ByteArray): Int
}

// why: Stream profile downloads in bounded reusable chunks instead of allocating the complete artifact.
public const val PERF_PROFILE_READ_BUFFER_BYTES: Int = 64 * 1_024
