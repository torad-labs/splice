package splice.observability.profile

internal object ProfileLimits {
    const val MAX_SECONDS = 300
    const val ARTIFACT_BYTES = 64L * 1_024 * 1_024
    const val STORE_BYTES = 256L * 1_024 * 1_024
    const val RETENTION_MS = 60L * 60 * 1_000
    const val MAX_RECORDS = 32
    const val CONTROL_SECONDS = 10L
    const val BUFFER_BYTES = 64 * 1_024
    const val METADATA_BYTES = 16L * 1_024
    val modes = setOf("cpu", "wall", "alloc", "lock")
}
