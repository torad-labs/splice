// NEW: the effort/summary vocabulary — the alias tables, the budget tiers, and the three
// post-resolution clamps. Split out of ResponsesRequestBuilder.kt (2026-08-17, concentration
// campaign): it has zero callers anywhere but the resolvers, and it touches no JsonObject — a pure
// vocabulary that owns its own tables. Every member kept its identical name and argument list.
package splice.dialect.responses

import splice.core.turn.ReasoningDisplay

/**
 * The effort/summary vocabulary: the alias tables, the budget tiers, and the three post-resolution
 * clamps. A type rather than the file-level functions it used to be (Kotlin main sources carry no
 * top-level functions) — folding them into [ResponsesRequestBuilder] instead would put it at 16
 * members against detekt's TooManyFunctions ceiling of 15. Every member reads only its arguments
 * and keeps its old name and argument list.
 */
public class ResponsesEffort {

    // the alias table IS the contract
    public fun normalizeEffort(raw: String?, ladder: EffortLadder): String? {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return null
        return when (ladder) {
            EffortLadder.CODEX -> normalizeCodexEffort(s)
            EffortLadder.GROK -> normalizeGrokEffort(s)
        }
    }

    private fun normalizeCodexEffort(s: String): String? = when (s) {
        "ultracode", "ultra" -> "max"
        "extra_high", "extra-high", "extrahigh" -> EFFORT_XHIGH
        "standard", "normal" -> EFFORT_MEDIUM
        "light", "fast" -> "low"
        "heavy", "extended" -> "high"
        in CODEX_EFFORTS -> s
        else -> null
    }

    private fun normalizeGrokEffort(s: String): String? = when (s) {
        // grok-4.6+ (xAI docs 2026-08): xhigh is the top rung; older groks clamp it to high upstream.
        "max", "ultra", "ultracode", "extra_high", "extra-high", "extrahigh", EFFORT_XHIGH,
        -> EFFORT_XHIGH
        "high", "heavy", "extended",
        -> "high"
        EFFORT_MEDIUM, "standard", "normal" -> EFFORT_MEDIUM
        "low", EFFORT_MINIMAL, "none", "off", "fast", "light" -> "low"
        else -> null
    }

    public fun normalizeSummary(raw: String?): String? {
        val s = raw?.trim()?.lowercase().orEmpty()
        return when {
            s.isEmpty() -> null
            s in SUMMARY_CANONICAL -> s
            s in SUMMARY_AS_DETAILED -> SUMMARY_DETAILED
            s in SUMMARY_AS_CONCISE -> SUMMARY_CONCISE
            s in SUMMARY_AS_NONE -> "none"
            else -> null
        }
    }

    // tier table
    public fun effortFromBudget(budget: Long, ladder: EffortLadder): String? = when (ladder) {
        EffortLadder.CODEX -> codexBudgetEffort(budget)
        EffortLadder.GROK -> when {
            budget >= BUDGET_MAX -> EFFORT_XHIGH
            budget >= BUDGET_HIGH -> "high"
            budget >= BUDGET_MEDIUM -> EFFORT_MEDIUM
            else -> "low"
        }
    }

    private fun codexBudgetEffort(budget: Long): String = when {
        budget >= BUDGET_MAX -> "max"
        budget >= BUDGET_XHIGH -> EFFORT_XHIGH
        budget >= BUDGET_HIGH -> "high"
        budget >= BUDGET_MEDIUM -> EFFORT_MEDIUM
        else -> "low"
    }

    /**
     * Visibility floor: never RAISES a deliberate low/medium/high pick, only floors none/minimal to
     * low so a hidden reasoning knob still surfaces something when showReasoning != off.
     */
    internal fun flooredForVisibility(effort: String?, showReasoning: ReasoningDisplay): String? {
        if (showReasoning.isOff) return effort
        val hidden = effort == EFFORT_MINIMAL || effort == "none"
        return if (hidden) "low" else effort
    }

    /** grok reasoning cannot be disabled — floor anything off the grok ladder to low. */
    internal fun flooredForGrok(effort: String?, ladder: EffortLadder): String? {
        if (ladder != EffortLadder.GROK) return effort
        return effort?.takeIf { it in GROK_EFFORTS } ?: "low"
    }

    /** Per-model effort ceiling: models matching the quirk regex reject effort=max — clamp to xhigh. */
    internal fun clampedForModelCeiling(effort: String?, upstreamModel: String, rejectMax: Regex?): String? {
        if (effort != "max" || rejectMax?.containsMatchIn(upstreamModel) != true) return effort
        return EFFORT_XHIGH
    }
}

// Effort/summary tokens shared by the alias tables and the resolvers.
private const val EFFORT_MEDIUM = "medium"
private const val EFFORT_XHIGH = "xhigh"
private const val EFFORT_MINIMAL = "minimal"

// internal: read by ResponsesReasoningKnobs.kt's resolveSummary (the v27 visibility fold).
internal const val SUMMARY_DETAILED = "detailed"
private const val SUMMARY_CONCISE = "concise"

// FILE SCOPE ON PURPOSE: the effort/summary alias tables — hoisted so the resolvers never allocate
// a fresh set per call on the request-build path.
private val GROK_EFFORTS = setOf("low", EFFORT_MEDIUM, "high", EFFORT_XHIGH)
private val CODEX_EFFORTS = setOf("none", EFFORT_MINIMAL, "low", EFFORT_MEDIUM, "high", EFFORT_XHIGH, "max")
private val SUMMARY_CANONICAL = setOf("auto", SUMMARY_CONCISE, SUMMARY_DETAILED, "none")

// v27 visibility fold: these weak/absent summaries floor to detailed when reasoning is shown.
// internal: read by ResponsesReasoningKnobs.kt's resolveSummary.
internal val summaryFloorToDetailed = setOf("none", "auto", SUMMARY_CONCISE)
private val SUMMARY_AS_DETAILED = setOf("full", "verbose", "long")
private val SUMMARY_AS_CONCISE = setOf("short", "brief")
private val SUMMARY_AS_NONE = setOf("off", "false", "0")

private const val BUDGET_MAX = 64_000L
private const val BUDGET_XHIGH = 32_000L
private const val BUDGET_HIGH = 10_000L
private const val BUDGET_MEDIUM = 2_000L
