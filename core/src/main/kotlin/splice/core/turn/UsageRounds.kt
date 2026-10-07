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
    history = UsageHistory(request = UsageRequest.NONE),
    reported = emptySet(),
)

/** One distinct-request fold shared by the gateway and code-mode runners. */
internal object UsageRounds {
    fun followedBy(prior: Usage, latest: Usage): Usage {
        if (latest.history.request == UsageRequest.NONE) {
            val total = prior + latest
            return if (prior.history.request == UsageRequest.POSTED) total.copy(reported = prior.reported) else total
        }
        if (prior.history.request == UsageRequest.NONE) return (prior + latest).copy(reported = latest.reported)
        val observations = latest.reported + prior.reported.intersect(setOf(UsageField.OUTPUT))
        return Usage(
            inputTokens = latest.inputTokens,
            outputTokens = prior.outputTokens + latest.outputTokens,
            cachedTokens = latest.cachedTokens,
            reasoningTokens = prior.reasoningTokens + latest.reasoningTokens,
            cacheWriteTokens = latest.cacheWriteTokens,
            localStep = prior.localStep || latest.localStep,
            codeModeDiverged = prior.codeModeDiverged || latest.codeModeDiverged,
            recordedOutputTokens = prior.recordedOutputTokens + latest.recordedOutputTokens,
            clientContext = contextAfter(prior, latest),
            history = UsageHistory(
                absorbed = prior.absorbed + prior.finalRound + latest.absorbed,
                cutRounds = prior.cutRounds + latest.cutRounds + missingBills(prior, latest, observations),
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
        if (latest.clientContext != null) return latest.clientContext
        if (UsageField.INPUT in latest.reported) return null
        return prior.takeIf { UsageField.INPUT in it.reported } ?: prior.clientContext
    }
}
