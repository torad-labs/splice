// NEW: V4-133 review — one head's spend for one UTC day, and the budget it is weighed against.
// BudgetLedger owns the lock both are read and mutated under; neither type locks anything itself.
package splice.usage.budgets

/** A head's budget as enforcement reads it: only a row with a daily_usd is a budget at all. */
internal data class Limit(val usd: Double, val action: String)

/** One UTC day's spend on one head: the priced USD, and the turns that had no price. */
internal class DayTally(val day: Long) {
    var usd: Double = 0.0
        private set
    var unpriced: Long = 0L
        private set

    /** True once the spend recorded before this daemon's boot has been folded in. */
    var seeded: Boolean = false
        private set

    /** The limit the operator was last told about today, or null before the first warning. */
    private var warnedAt: Double? = null

    /** The unpriced models already named in the log today. */
    private val unpricedModels: MutableSet<String> = HashSet()

    /** One turn at [usd], or an unpriced one when [usd] is null. */
    fun add(usd: Double?) {
        if (usd != null) this.usd += usd else unpriced += 1
    }

    /** Folds [before] in, once: the second fold of a racing pair is a no-op. */
    fun seed(before: DayTally) {
        if (seeded) return
        usd += before.usd
        unpriced += before.unpriced
        seeded = true
    }

    /** True the first time today [model] could not be priced. */
    fun firstUnpriced(model: String): Boolean = unpricedModels.add(model)

    /** True when [limit] has not been told today, which it now has. */
    fun firstWarning(limit: Double): Boolean {
        if (warnedAt == limit) return false
        warnedAt = limit
        return true
    }
}
