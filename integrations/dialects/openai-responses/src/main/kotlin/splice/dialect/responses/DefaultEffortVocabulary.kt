// NEW: OpenAI Responses platform vocabulary (minimal..xhigh plus Claude Code aliases). Dialect-owned,
// not a vendor fact — CodexQuirks uses this default; grok supplies GrokEffortVocabulary.
package splice.dialect.responses

import splice.upstream.EffortVocabulary

internal class DefaultEffortVocabulary : EffortVocabulary {
    override fun normalize(raw: String): String? = when (raw) {
        "ultracode", "ultra" -> "max"
        "extra_high", "extra-high", "extrahigh" -> RESPONSES_EFFORT_XHIGH
        "standard", "normal" -> RESPONSES_EFFORT_MEDIUM
        "light", "fast" -> "low"
        "heavy", "extended" -> "high"
        in RUNGS -> raw
        else -> null
    }

    override fun fromBudget(budget: Long): String = when {
        budget >= RESPONSES_BUDGET_MAX -> "max"
        budget >= RESPONSES_BUDGET_XHIGH -> RESPONSES_EFFORT_XHIGH
        budget >= RESPONSES_BUDGET_HIGH -> "high"
        budget >= RESPONSES_BUDGET_MEDIUM -> RESPONSES_EFFORT_MEDIUM
        else -> "low"
    }
}

private const val RESPONSES_EFFORT_MEDIUM = "medium"
private const val RESPONSES_EFFORT_XHIGH = "xhigh"
private const val RESPONSES_EFFORT_MINIMAL = "minimal"
private val RUNGS = setOf(
    "none",
    RESPONSES_EFFORT_MINIMAL,
    "low",
    RESPONSES_EFFORT_MEDIUM,
    "high",
    RESPONSES_EFFORT_XHIGH,
    "max",
)
private const val RESPONSES_BUDGET_MAX = 64_000L
private const val RESPONSES_BUDGET_XHIGH = 32_000L
private const val RESPONSES_BUDGET_HIGH = 10_000L
private const val RESPONSES_BUDGET_MEDIUM = 2_000L
