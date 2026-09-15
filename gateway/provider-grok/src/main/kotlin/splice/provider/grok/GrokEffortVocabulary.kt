// NEW: Grok owns its effort alias table, budget tiers, and cannot-disable floor. The responses
// dialect calls EffortVocabulary; it does not know Grok rungs.
package splice.provider.grok

import splice.spi.EffortVocabulary

public class GrokEffortVocabulary : EffortVocabulary {
    override fun normalize(raw: String): String? = when (raw) {
        "max", "ultra", "ultracode", "extra_high", "extra-high", "extrahigh", XHIGH -> XHIGH
        "high", "heavy", "extended" -> "high"
        MEDIUM, "standard", "normal" -> MEDIUM
        "low", MINIMAL, "none", "off", "fast", "light" -> "low"
        else -> null
    }

    override fun fromBudget(budget: Long): String = when {
        budget >= BUDGET_MAX -> XHIGH
        budget >= BUDGET_HIGH -> "high"
        budget >= BUDGET_MEDIUM -> MEDIUM
        else -> "low"
    }

    override fun floor(effort: String?): String = effort?.takeIf { it in RUNGS } ?: "low"

    override fun omitWhenDisabled(): Boolean = false
}

private const val MEDIUM = "medium"
private const val XHIGH = "xhigh"
private const val MINIMAL = "minimal"
private val RUNGS = setOf("low", MEDIUM, "high", XHIGH)
private const val BUDGET_MAX = 64_000L
private const val BUDGET_HIGH = 10_000L
private const val BUDGET_MEDIUM = 2_000L
