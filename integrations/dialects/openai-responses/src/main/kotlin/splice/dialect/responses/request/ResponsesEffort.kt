// NEW: the effort/summary vocabulary — the alias tables, the budget tiers, and the three
// post-resolution clamps. Split out of ResponsesRequestBuilder.kt (2026-08-17, concentration
// campaign): it has zero callers anywhere but the resolvers, and it touches no JsonObject — a pure
// vocabulary that owns its own tables. Every member kept its identical name and argument list.
package splice.dialect.responses.request

import splice.core.turn.ReasoningDisplay
import splice.upstream.EffortVocabulary

/**
 * The effort/summary vocabulary: vendor rungs arrive through [EffortVocabulary]; summary aliases
 * and the visibility/model clamps stay dialect-neutral.
 */
internal class ResponsesEffort {

    public fun normalizeEffort(raw: String?, vocabulary: EffortVocabulary): String? {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return null
        return vocabulary.normalize(s)
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

    public fun effortFromBudget(budget: Long, vocabulary: EffortVocabulary): String? =
        vocabulary.fromBudget(budget)

    /**
     * Visibility floor: never RAISES a deliberate low/medium/high pick, only floors none/minimal to
     * low so a hidden reasoning knob still surfaces something when showReasoning != off.
     */
    internal fun flooredForVisibility(effort: String?, showReasoning: ReasoningDisplay): String? {
        if (showReasoning.isOff) return effort
        val hidden = effort == EFFORT_MINIMAL || effort == "none"
        return if (hidden) "low" else effort
    }

    internal fun flooredForVendor(effort: String?, vocabulary: EffortVocabulary): String? =
        vocabulary.floor(effort)

    /** Per-model effort ceiling: models matching the quirk regex reject effort=max — clamp to xhigh. */
    internal fun clampedForModelCeiling(effort: String?, upstreamModel: String, rejectMax: Regex?): String? {
        if (effort != "max" || rejectMax?.containsMatchIn(upstreamModel) != true) return effort
        return EFFORT_XHIGH
    }
}

// FILE SCOPE ON PURPOSE: the remaining summary alias tables — hoisted so the resolvers never
// allocate a set per call. Effort rungs live on EffortVocabulary now.
private const val EFFORT_XHIGH = "xhigh"
private const val EFFORT_MINIMAL = "minimal"
internal const val SUMMARY_DETAILED = "detailed"
private const val SUMMARY_CONCISE = "concise"
internal val summaryFloorToDetailed = setOf("none", "auto", SUMMARY_CONCISE)
private val SUMMARY_CANONICAL = setOf("auto", SUMMARY_CONCISE, SUMMARY_DETAILED, "none")
private val SUMMARY_AS_DETAILED = setOf("full", "verbose", "long")
private val SUMMARY_AS_CONCISE = setOf("short", "brief")
private val SUMMARY_AS_NONE = setOf("off", "false", "0")
