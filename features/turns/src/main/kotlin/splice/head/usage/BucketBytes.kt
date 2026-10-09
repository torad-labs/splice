package splice.head.usage

/** One hour's request sizes: what clients sent and what the head sent upstream. */
public data class BucketBytes(
    val reqBytes: Long = 0,
    val upstreamBytes: Long = 0,
)
