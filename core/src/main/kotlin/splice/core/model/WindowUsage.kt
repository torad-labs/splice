package splice.core.model

/** Resolves the three client-visible input buckets; upstream accounting and output stay in real units. */
public class WindowUsage(private val catalog: ModelCatalog) {
    private val selectors = SelectorWindow(catalog)

    /** A stated divisor can scale either way; a plausible divisor can only raise counts. */
    public fun of(id: String, evidence: ClientWindow, learned: Long? = null): UsageSizing {
        val declared = catalog.contextWindowFor(id)
        val divisor = divisor(id, evidence, declared)
        val raw = UsageSizing(divisor.tokens, declaredWindow = declared, source = divisor.source)
        if (declared <= 0 || divisor.tokens <= 0) return raw

        val budget = CompactionBudgets.forRow(catalog, id)
        val realTarget = if (budget == null) declared else (declared - budget.totalTokens).coerceAtLeast(1)
        val clientTarget = if (budget == null) divisor.tokens else ClaudeCodeCompactThreshold.tokens(divisor.tokens)
        return if (clientTarget < realTarget && !canDiscount(id, divisor.source, learned)) {
            raw.copy(contextWindow = declared)
        } else {
            raw.copy(factor = clientTarget.toDouble() / realTarget)
        }
    }

    private fun divisor(id: String, evidence: ClientWindow, declared: Long): Divisor = when (evidence) {
        is ClientWindow.Stated -> Divisor(evidence.tokens, WindowSource.REQUEST)
        is ClientWindow.Bound -> Divisor(evidence.tokens, WindowSource.HINT)
        is ClientWindow.Hint -> {
            val fixed = selectors.fixed(id)
            when {
                fixed != null -> Divisor(fixed, WindowSource.REQUEST)
                evidence.tokens != null -> Divisor(evidence.tokens, WindowSource.HINT)
                else -> Divisor(defaultWindow(id, declared), WindowSource.NONE)
            }
        }
    }

    private fun defaultWindow(id: String, declared: Long): Long =
        if (catalog.client.open) declared else catalog.clientContextWindowFor(id)

    // An open provider does not tell its client which model or window to use. Preserve raw counts
    // unless it is safe to raise them or the Claude session has also posted its divisor.
    private fun canDiscount(id: String, source: WindowSource, learned: Long?): Boolean =
        source == WindowSource.REQUEST && (!catalog.client.open || learnedClaude(id, learned))

    private fun learnedClaude(id: String, learned: Long?): Boolean =
        learned != null && learned > 0 && id.startsWith(CLIENT_OWN_ID_PREFIX) && !catalog.presented.selectorSized(id)

    private data class Divisor(val tokens: Long, val source: WindowSource)
}
