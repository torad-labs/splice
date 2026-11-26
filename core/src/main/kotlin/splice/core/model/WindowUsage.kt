package splice.core.model

/** Resolves the row's declared capacity into the units a request can safely be told. */
public class WindowUsage(private val catalog: ModelCatalog) {
    private val selectors = SelectorWindow(catalog)

    /** A stated divisor can scale either way; an inferred divisor can only raise counts. */
    public fun of(
        id: String,
        evidence: ClientWindow,
        selector: String? = id,
        learned: Long? = null,
    ): UsageSizing {
        val hinted = (evidence as? ClientWindow.Hint)?.tokens
        val session = if (catalog.client.open) learned else hinted
        val fixed = selector?.let { selectors.fixed(id, it) }
        val stated = (evidence as? ClientWindow.Stated)?.tokens ?: fixed
        return sized(id, session, stated, source(evidence, stated))
    }

    internal fun known(id: String, sessionWindow: Long?, requestWindow: Long?): UsageSizing =
        sized(id, sessionWindow, requestWindow, WindowSource.REQUEST)

    private fun source(evidence: ClientWindow, stated: Long?): WindowSource = when {
        stated != null -> WindowSource.REQUEST
        evidence is ClientWindow.Hint && evidence.tokens != null -> WindowSource.HINT
        else -> WindowSource.NONE
    }

    private fun sized(id: String, sessionWindow: Long?, stated: Long?, source: WindowSource): UsageSizing {
        val declared = catalog.contextWindowFor(id)
        val window = catalog.clientContextWindowFor(id, sessionWindow, stated)
        val raw = UsageSizing(window, declaredWindow = declared, source = source)
        if (declared <= 0 || window <= 0) return raw
        if (!canSize(id, sessionWindow)) return raw

        val budget = CompactionBudgets.forRow(catalog, id)
        val realTarget = if (budget == null) declared else (declared - budget.totalTokens).coerceAtLeast(1)
        val clientTarget = if (budget == null) window else ClaudeCodeCompactThreshold.tokens(window)
        return if (source != WindowSource.REQUEST && clientTarget < realTarget) {
            raw.copy(contextWindow = declared)
        } else {
            raw.copy(factor = clientTarget.toDouble() / realTarget)
        }
    }

    private fun canSize(id: String, sessionWindow: Long?): Boolean {
        if (!catalog.client.open) return true
        val posted = sessionWindow?.takeIf { it > 0 } != null
        return posted && id.startsWith(CLIENT_OWN_ID_PREFIX) && !catalog.presented.selectorSized(id)
    }
}
