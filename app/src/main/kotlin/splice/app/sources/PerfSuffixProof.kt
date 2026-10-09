package splice.app.sources

/** The span of a generation that account indexing proved by hash, from [start] to the end of the file it read. */
internal data class PerfSuffixProof(
    val start: Long = 0L,
    val suffix: ByteArray? = null,
)
