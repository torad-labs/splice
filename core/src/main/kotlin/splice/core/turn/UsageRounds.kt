// NEW: explicit request ownership and one shared fold keep absent bills separate from no request.
package splice.core.turn

/** Whether this usage owns a final upstream request, independently of whether its bill arrived. */
public enum class UsageRequest { POSTED, NONE }

/** The request ownership and earlier requests retained beside the final token buckets. */
public data class UsageHistory(
    val absorbed: AbsorbedRounds = AbsorbedRounds(),
    val cutRounds: Long = 0,
    val request: UsageRequest = UsageRequest.POSTED,
) {
    public operator fun plus(other: UsageHistory): UsageHistory = UsageHistory(
        absorbed = absorbed + other.absorbed,
        cutRounds = cutRounds + other.cutRounds,
        request = if (request == UsageRequest.POSTED) request else other.request,
    )
}

/** No upstream request belongs to this immutable value. It is the identity of the round merge. */
public val noRequestUsage: Usage = Usage(
    origin = UsageOrigin(history = UsageHistory(request = UsageRequest.NONE)),
    reported = emptySet(),
)

/** One distinct-request fold shared by the gateway and code-mode runners. */
internal object UsageRounds {
    fun followedBy(prior: Usage, latest: Usage): Usage {
        if (latest.origin.history.request == UsageRequest.NONE) {
            val total = prior + latest
            val posted = prior.origin.history.request == UsageRequest.POSTED
            return if (posted) total.copy(reported = prior.reported) else total
        }
        if (prior.origin.history.request == UsageRequest.NONE) return (prior + latest).copy(reported = latest.reported)
        val observations = latest.reported + prior.reported.intersect(setOf(UsageField.OUTPUT))
        return Usage(
            inputTokens = latest.inputTokens,
            outputTokens = prior.outputTokens + latest.outputTokens,
            cachedTokens = latest.cachedTokens,
            reasoningTokens = prior.reasoningTokens + latest.reasoningTokens,
            cacheWriteTokens = latest.cacheWriteTokens,
            origin = UsageOrigin(
                localStep = prior.origin.localStep || latest.origin.localStep,
                codeModeDiverged = prior.origin.codeModeDiverged || latest.origin.codeModeDiverged,
                recordedOutputTokens = prior.origin.recordedOutputTokens + latest.origin.recordedOutputTokens,
                clientContext = contextAfter(prior, latest),
                history = UsageHistory(
                    absorbed = prior.absorbed + prior.finalRound + latest.absorbed,
                    cutRounds = prior.cutRounds + latest.cutRounds + missingBills(prior, latest, observations),
                ),
            ),
            reported = observations,
        )
    }

    private fun missingBills(prior: Usage, latest: Usage, observations: Set<UsageField>): Long {
        if (observations.size < UsageField.entries.size) return 0
        val before = if (prior.reported.size < UsageField.entries.size) 1L else 0L
        val final = if (latest.reported.size < UsageField.entries.size) 1L else 0L
        return before + final
    }

    private fun contextAfter(prior: Usage, latest: Usage): Usage? {
        latest.origin.clientContext?.let { return it }
        if (UsageField.INPUT in latest.reported) return null
        return prior.takeIf { UsageField.INPUT in it.reported } ?: prior.origin.clientContext
    }
}
