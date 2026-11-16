// NEW: server mode (docs/specs/server-mode.md, F5, person-budgets). Whether a person's next turn is within their
// budgets: their own (or, when the operator set them none, `[server.budgets] person_daily_usd`) and their team's
// today. A budget is over once what was spent in its day or its month reached its limit. One that blocks refuses the
// turn before it reaches any upstream, in words that name the budget; one that warns serves it and only reads over.
package splice.server.budget

import splice.core.config.KnobWords
import splice.core.topology.ServerBudgets
import splice.core.topology.ServerTeams
import splice.server.ServerPeople
import splice.server.ledger.UsageTables

/** One budget as it stands now: what it allows, and what was spent against it today and this month. */
internal data class BudgetReading(val setting: BudgetSetting, val spentToday: Double, val spentMonth: Double) {
    /** Whether a limit of it is reached. */
    val over: Boolean
        get() = reached(setting.dailyUsd, spentToday) || reached(setting.monthlyUsd, spentMonth)

    /** Whether it refuses the next turn. */
    val blocks: Boolean get() = over && setting.action == KnobWords.BUDGET_BLOCK

    private fun reached(limit: Double?, spent: Double): Boolean = limit != null && spent >= limit
}

internal class BudgetGate(
    private val book: BudgetBook,
    private val spend: ServerSpend,
    private val people: ServerPeople,
    private val teams: ServerTeams,
    private val defaults: ServerBudgets,
) {
    /** Every budget set, as it stands now. */
    fun readings(): List<BudgetReading> = book.all().map(::reading)

    /** Whether any budget holds [person]: one of their own, the default, or their team's. */
    fun holds(person: String): Boolean = budgetsOf(person).isNotEmpty()

    /** Why [person]'s next turn is refused, naming the budget it would pass; null while none of theirs blocks. */
    fun refusal(person: String): String? = budgetsOf(person).map(::reading).firstOrNull { it.blocks }?.let(::why)

    /** The budgets [person] is held to now: their own or the default, then their team's. */
    private fun budgetsOf(person: String): List<BudgetSetting> {
        val own = book.of(SpendScope.PERSON, person) ?: defaults.personDailyUsd?.let { daily ->
            BudgetSetting(SpendScope.PERSON.wire, person, dailyUsd = daily, action = defaults.action)
        }
        val team = people.team(person, teams)?.let { book.of(SpendScope.TEAM, it) }
        return listOfNotNull(own, team)
    }

    private fun reading(setting: BudgetSetting): BudgetReading {
        val scope = SpendScope.entries.first { it.wire == setting.scope }
        return BudgetReading(setting, spend.today(scope, setting.name), spend.month(scope, setting.name))
    }

    private fun why(reading: BudgetReading): String {
        val setting = reading.setting
        val daily = setting.dailyUsd?.takeIf { reading.spentToday >= it }
        val (period, limit, spent) = if (daily != null) {
            Triple("daily", daily, reading.spentToday)
        } else {
            Triple("monthly", setting.monthlyUsd ?: 0.0, reading.spentMonth)
        }
        val whose = if (setting.scope == SpendScope.TEAM.wire) "team ${setting.name}" else setting.name
        val used = "${UsageTables.amount(spent)} of ${UsageTables.amount(limit)} USD"
        return "the $period budget of $whose is spent: $used " +
            "at the models' rate cards; ask the server's operator to raise it, or wait for the next ${days(period)}"
    }

    private fun days(period: String): String = if (period == "daily") "day" else "month"
}
