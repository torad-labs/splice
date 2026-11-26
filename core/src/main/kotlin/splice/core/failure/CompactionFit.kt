package splice.core.failure

/** The information splice has for a shorter compaction: an unscaled input estimate, model window, and reply allowance. */
public data class CompactionFit(
    val inputTokens: Long,
    val windowTokens: Long?,
    val summaryTokens: Long,
) {
    internal fun refusal(cause: FailureCause.Provider.TooLong, head: String, spoken: Spoken): Said {
        val explanation = when {
            cause.whileAnswering -> "The summary filled $head's context window before it finished."
            cause.limitTokens != null -> "The model on $head holds ${spoken.tokens(cause.limitTokens)} tokens."
            else -> "This conversation is too long for $head to summarize."
        }
        return refusal(explanation, cause.inputTokens, cause.limitTokens)
    }

    internal fun refusal(explanation: String, actual: Long? = null, limit: Long? = null): Said {
        val measured = actual?.takeIf { it > 0 }
        val input = (measured ?: inputTokens).coerceAtLeast(1)
        val estimate = if (measured == null) "about " else ""
        val window = limit?.takeIf { it > 0 } ?: windowTokens
        val fits = window?.let { (it - summaryTokens).coerceAtLeast(0) }
        // The provider refused, even when our estimate shows no excess. Ask for a fifth of the estimated input
        // then, similar to the client's round-based trim when the error names no size.
        val target = fits?.takeIf { it < input } ?: (input - (input / FALLBACK_TRIM_DIVISOR).coerceAtLeast(1))
        return Said(
            "The summary prompt is too long: $estimate$input tokens > $target for a shorter retry",
            next = Next.Then(explanation),
        )
    }
}

// When the measured sizes do not explain a refusal, target a trim of 20% of the input estimate.
private const val FALLBACK_TRIM_DIVISOR = 5
