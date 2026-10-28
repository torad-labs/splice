// NEW: PassthroughRequestBuilder.effortLadder's two split-out rules — split out of
// PassthroughRequestBuilder.kt (2026-08-17, concentration campaign). It was already a standalone
// collaborator that closes over nothing; it was denied its own file only because the prior wave
// was solving a function-count wall, not a concentration one. Must be a SEPARATE file from
// PassthroughThinking.kt: merging the two types into one file is the exact sideways move this
// campaign forbids. Every relocated member kept its identical name and argument list.
package splice.dialect.anthropic

import splice.core.util.LogSink
import java.util.concurrent.atomic.AtomicBoolean

private const val EFFORT_LOW = "low"
private const val EFFORT_HIGH = "high"
private const val EFFORT_MAX = "max"
private const val HIGH_BUDGET_FLOOR = 8_192L
private const val MAX_BUDGET_FLOOR = 24_576L

/** splice's effort when nothing names one (Marcos, Oct 28: "effort at high by default"). */
private const val EFFORT_DEFAULT = EFFORT_HIGH

internal class PassthroughEffortLadder {

    /** splice's effort words, cheapest first: a vendor rung is placed among them by its position here. */
    private val order = listOf("none", "minimal", EFFORT_LOW, "medium", EFFORT_HIGH, "xhigh", EFFORT_MAX)

    /** [word] on the vendor's [rungs]: the highest rung not above it, so no turn costs more than was asked (Marcos,
     *  Oct 28: "We should never make configuration that might be expensive for the user without them knowing"),
     *  else the cheapest. The word itself where there are no rungs; null for a word that is not an effort. */
    fun onRungs(word: String?, rungs: List<String>?): String? {
        val asked = word?.trim()?.lowercase()
        val rank = asked?.let(order::indexOf) ?: -1
        return when {
            asked == null || rank < 0 -> null
            rungs == null -> asked
            else -> rungs.filter { order.indexOf(it) in 0..rank }.maxByOrNull(order::indexOf) ?: rungs.firstOrNull()
        }
    }

    /** The configured effort on [rungs] for a turn whose request names none, splice's default high when nothing is
     *  configured, and SCH-006's cheapest rung for a word that is no effort at all. */
    fun configuredEffort(
        configEffort: String?,
        providerTag: String,
        warned: AtomicBoolean,
        log: LogSink,
        rungs: List<String>?,
    ): String {
        if (configEffort == null) return onRungs(EFFORT_DEFAULT, rungs) ?: EFFORT_DEFAULT
        return onRungs(configEffort, rungs) ?: fallbackEffort(configEffort, providerTag, warned, log, rungs)
    }

    /** The budget-to-rung mapping, for a turn whose request names no effort of its own. */
    fun budgetEffort(budget: Long?): String = when {
        budget == null -> EFFORT_MAX
        budget >= MAX_BUDGET_FLOOR -> EFFORT_MAX
        budget >= HIGH_BUDGET_FLOOR -> EFFORT_HIGH
        budget > 0L -> EFFORT_LOW
        else -> EFFORT_MAX
    }

    /** SCH-006's unrecognized-configEffort fallback. An effort value valid for another provider's
     *  vocab (CODEX_REASONING_EFFORT's medium) but not one of this profile's rungs must never
     *  silently ESCALATE to the priciest rung — it falls to the CHEAPEST one instead (never pricier
     *  than whatever the operator actually asked for), and the substitution is logged ONCE per
     *  builder lifetime via [warned] rather than once per turn. Rungs come from the quirks profile
     *  (cheapest first). null rungs means no vendor ladder: the trimmed token rides, or MAX when
     *  absent. */
    fun fallbackEffort(
        configEffort: String?,
        providerTag: String,
        warned: AtomicBoolean,
        log: LogSink,
        rungs: List<String>?,
    ): String {
        val trimmed = configEffort?.trim()?.lowercase() ?: return EFFORT_MAX
        return when {
            rungs == null -> trimmed
            trimmed in rungs -> trimmed
            else -> {
                val cheapest = rungs.firstOrNull() ?: EFFORT_LOW
                if (warned.compareAndSet(false, true)) {
                    log(
                        "[$providerTag] configured effort '$trimmed' is not a vendor rung " +
                            "(${rungs.joinToString("|")}); using '$cheapest' instead of silently " +
                            "escalating to '$EFFORT_MAX'\n",
                    )
                }
                cheapest
            }
        }
    }
}
