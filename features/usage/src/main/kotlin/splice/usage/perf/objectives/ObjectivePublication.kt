package splice.usage.perf.objectives

/** Bounded local lifecycle persistence only. Implementations must not query, send, or re-enter configuration. */
internal fun interface ObjectivePublication {
    fun publish(): Boolean
}

internal enum class ObjectivePublicationResult { PUBLISHED, SETTINGS_CHANGED, FAILED }
