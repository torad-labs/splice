package splice.head.perf

/** Validation, immutable-copy admission and enqueue share the producer checkpoint boundary. */
internal fun interface RequestEvidenceAdmission {
    fun record(): Boolean
}
