// NEW: OpenAI Responses platform vocabulary (minimal..xhigh plus Claude Code aliases). Dialect-owned,
// not a vendor fact — CodexQuirks uses this default; grok supplies GrokEffortVocabulary.
package splice.dialect.responses.request

import splice.upstream.EffortVocabulary

public class DefaultEffortVocabulary : EffortVocabulary {
    override fun normalize(raw: String): String? = when (raw) {
        "ultracode", "ultra" -> "max"
        "extra_high", "extra-high", "extrahigh" -> XHIGH
        "standard", "normal" -> MEDIUM
        "light", "fast" -> "low"
        "heavy", "extended" -> "high"
        in RUNGS -> raw
        else -> null
    }

    override fun fromBudget(budget: Long): String = when {
        budget >= BUDGET_MAX -> "max"
        budget >= BUDGET_XHIGH -> XHIGH
        budget >= BUDGET_HIGH -> "high"
        budget >= BUDGET_MEDIUM -> MEDIUM
        else -> "low"
    }
}

private const val MEDIUM = "medium"
private const val XHIGH = "xhigh"
private const val MINIMAL = "minimal"
private val RUNGS = setOf("none", MINIMAL, "low", MEDIUM, "high", XHIGH, "max")
private const val BUDGET_MAX = 64_000L
private const val BUDGET_XHIGH = 32_000L
private const val BUDGET_HIGH = 10_000L
private const val BUDGET_MEDIUM = 2_000L
