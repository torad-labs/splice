package splice.core.model

/** Resolves the row's declared capacity into the units a request can safely be told. */
public class WindowUsage(private val catalog: ModelCatalog) {
    /** A stated divisor can scale either way; an inferred divisor can only raise counts. */
    public fun of(id: String, evidence: ClientWindow): UsageSizing = when (evidence) {
        is ClientWindow.Stated -> known(id, null, evidence.tokens)
        is ClientWindow.Hint -> sized(id, evidence.tokens, null, hintOnly = true)
    }

    internal fun known(id: String, sessionWindow: Long?, requestWindow: Long?): UsageSizing =
        sized(id, sessionWindow, requestWindow, hintOnly = false)

    private fun sized(id: String, sessionWindow: Long?, requestWindow: Long?, hintOnly: Boolean): UsageSizing {
        val declared = catalog.contextWindowFor(id)
        val window = catalog.clientContextWindowFor(id, sessionWindow, requestWindow)
        if (declared <= 0 || window <= 0) return UsageSizing(window)
        if (!canSize(id, sessionWindow, requestWindow)) return UsageSizing(window)

        val budget = CompactionBudgets.forRow(catalog, id)
        val realTarget = if (budget == null) declared else (declared - budget.totalTokens).coerceAtLeast(1)
        val clientTarget = if (budget == null) window else ClaudeCodeCompactThreshold.tokens(window)
        return if (hintOnly && clientTarget < realTarget) {
            // The hint is not a divisor for this request. Report in the model's own units.
            UsageSizing(declared)
        } else {
            UsageSizing(window, clientTarget.toDouble() / realTarget)
        }
    }

    private fun canSize(id: String, sessionWindow: Long?, requestWindow: Long?): Boolean {
        if (!catalog.client.open || requestWindow != null) return true
        val posted = sessionWindow?.takeIf { it > 0 } != null
        return posted && id.startsWith(CLIENT_OWN_ID_PREFIX) && !catalog.presented.selectorSized(id)
    }
}
