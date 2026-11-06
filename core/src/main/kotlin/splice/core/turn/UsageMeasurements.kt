package splice.core.turn

public enum class UsageMeasure { INPUT, OUTPUT, CACHE_READ, CACHE_WRITE, CACHE_WRITE_1H }

/** Measurement presence covers the total owned bill, not just the last contributing round. */
public object UsageMeasurements {
    /** Partial updates preserve an earlier fact when the other update has no contribution to that field. */
    public fun added(prior: Usage, latest: Usage): Set<UsageMeasure> = UsageMeasure.entries.filterTo(linkedSetOf()) {
        val left = contributes(prior, it)
        val right = contributes(latest, it)
        listOf(left || right, !left || it in prior.measured, !right || it in latest.measured).all { known -> known }
    }

    public fun combined(prior: Usage, latest: Usage): Set<UsageMeasure> = when {
        emptyIdentity(prior) -> latest.measured
        emptyIdentity(latest) -> prior.measured
        else -> prior.measured.intersect(latest.measured)
    }

    private fun contributes(usage: Usage, field: UsageMeasure): Boolean {
        if (emptyIdentity(usage)) return false
        val (value, billing) = when (field) {
            UsageMeasure.INPUT -> usage.inputTokens to UsageField.INPUT
            UsageMeasure.OUTPUT -> usage.outputTokens to UsageField.OUTPUT
            UsageMeasure.CACHE_READ -> usage.cachedTokens to UsageField.CACHED
            UsageMeasure.CACHE_WRITE -> usage.cacheWriteTokens to UsageField.CACHE_WRITE
            UsageMeasure.CACHE_WRITE_1H -> usage.cacheWriteHourlyTokens to UsageField.CACHE_WRITE
        }
        return listOf(value != 0L, billing in usage.reported, field in usage.measured).any { it }
    }

    private fun emptyIdentity(usage: Usage): Boolean = listOf(
        usage.origin.history.request == UsageRequest.NONE,
        usage.absorbed == AbsorbedRounds(),
        usage.cutRounds == 0L,
        listOf(
            usage.inputTokens,
            usage.outputTokens,
            usage.cachedTokens,
            usage.reasoningTokens,
            usage.cacheWriteTokens,
            usage.cacheWriteHourlyTokens,
            usage.origin.recordedOutputTokens,
        ).all { it == 0L },
    ).all { it }
}
