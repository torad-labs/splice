// NEW: PassthroughRequestBuilder.effortLadder's two split-out rules — split out of
// PassthroughRequestBuilder.kt (2026-08-17, concentration campaign). It was already a standalone
// collaborator that closes over nothing; it was denied its own file only because the prior wave
// was solving a function-count wall, not a concentration one. Must be a SEPARATE file from
// PassthroughThinking.kt: merging the two types into one file is the exact sideways move this
// campaign forbids. Every relocated member kept its identical name and argument list.
package splice.dialect.passthrough

import splice.core.util.LogSink
import java.util.concurrent.atomic.AtomicBoolean

// Kimi effort ladder vocab — top-level (not a class member) because PassthroughEffortLadder is a
// separate collaborator sized against detekt's TooManyFunctions budget.
private const val EFFORT_LOW = "low"
private const val EFFORT_HIGH = "high"
private const val EFFORT_MAX = "max"
private const val HIGH_BUDGET_FLOOR = 8_192L
private const val MAX_BUDGET_FLOOR = 24_576L
private val KIMI_EFFORTS = setOf(EFFORT_LOW, EFFORT_HIGH, EFFORT_MAX)

internal class PassthroughEffortLadder {

    /** The budget-to-rung mapping. A null budget (thinking present, no budget_tokens) is the
     *  wire-frozen EFFORT_MAX case KIMI BYTE-IDENTITY requires — see effortLadder's KDoc. */
    fun budgetEffort(budget: Long?): String = when {
        budget == null -> EFFORT_MAX
        budget >= MAX_BUDGET_FLOOR -> EFFORT_MAX
        budget >= HIGH_BUDGET_FLOOR -> EFFORT_HIGH
        budget > 0L -> EFFORT_LOW
        else -> EFFORT_MAX
    }

    /** SCH-006's unrecognized-configEffort fallback. An effort value valid for another provider's
     *  vocab (CODEX_REASONING_EFFORT's "medium") but not one of kimi's own {low, high, max} rungs
     *  must never silently ESCALATE to the priciest rung — it falls to the CHEAPEST one instead
     *  (never pricier than whatever the operator actually asked for), and the substitution is
     *  logged ONCE per builder lifetime via [warned] (an AtomicBoolean owned by the calling builder
     *  instance, not by this type) rather than once per turn. */
    fun fallbackEffort(
        configEffort: String?,
        providerTag: String,
        warned: AtomicBoolean,
        log: LogSink,
    ): String {
        val trimmed = configEffort?.trim()?.lowercase() ?: return EFFORT_MAX
        if (trimmed in KIMI_EFFORTS) return trimmed
        if (warned.compareAndSet(false, true)) {
            log(
                "[$providerTag] configured effort '$trimmed' is not a kimi rung (low|high|max) — " +
                    "using '$EFFORT_LOW' instead of silently escalating to '$EFFORT_MAX'\n",
            )
        }
        return EFFORT_LOW
    }
}
