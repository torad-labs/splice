package splice.core.perf.observation

/** Oversized provenance is unknown, never a truncated build or configuration identifier. */
internal object PerfProvenance {
    fun bounded(identity: PerfIdentity): PerfIdentity = identity.copy(
        bootId = field(identity.bootId),
        build = field(identity.build),
        version = field(identity.version),
        configRevision = field(identity.configRevision),
    )

    private fun field(value: String): String =
        value.takeIf { it.length <= PerfObservationLimits.MAX_LABEL } ?: "unknown"
}
