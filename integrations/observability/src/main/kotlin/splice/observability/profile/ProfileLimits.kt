package splice.observability.profile

internal object ProfileLimits {
    // Limit an explicit capture to five minutes so optional deep profiling cannot run indefinitely.
    const val MAX_SECONDS = 300

    // Stop artifact copies at 64 MiB; backend chunk retention alone does not bound the final file.
    const val ARTIFACT_BYTES = 64L * 1_024 * 1_024

    // Budget 256 MiB for published artifacts, reserving one maximum-size capture before starting another.
    const val ARTIFACT_STORE_BYTES = 256L * 1_024 * 1_024

    // Expire artifacts after one hour to keep optional diagnostic files from becoming permanent retained state.
    const val ARTIFACT_RETENTION_MS = 60L * 60 * 1_000

    // Retain at most 32 profile records so many small captures cannot grow metadata without bound.
    const val MAX_RECORDS = 32

    // Bound each capture-control phase and native controller to ten seconds, including worker shutdown waits.
    const val CONTROL_SECONDS = 10L

    // Stream artifacts through a leased 64 KiB buffer instead of loading the maximum-size artifact into memory.
    const val COPY_BUFFER_BYTES = 64 * 1_024

    // Reserve 16 KiB per profile record and independent metadata reply within the shared work budget.
    const val PROFILE_METADATA_BYTES = 16L * 1_024
    val modes = setOf("cpu", "wall", "alloc", "lock")
}
