// NEW: server mode (docs/specs/server-mode.md, F5, person-budgets). What each person and each team has spent today and
// this month, on the server's clock, counted from the ledger's rows as they are written and, at boot, from every row
// the ledger already holds: so a restart forgets no spend. A row is a team's spend when it ran under that team, and it
// counts at its price from the model's rate card; a row with no price counts nothing.
package splice.server.budget

import splice.server.ledger.LedgerRow
import splice.server.ledger.RowWatch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Whom a budget or a spend is of: one person, or everyone on one team. */
internal enum class SpendScope(public val wire: String) {
    PERSON("person"),
    TEAM("team"),
}

internal class ServerSpend(private val clock: Clock, private val zone: ZoneId = ZoneId.systemDefault()) : RowWatch {
    // USD by scope, name and day or month, as `person|ada@team.example|2026-11-16` and `team|core|2026-11`.
    private val spent = ConcurrentHashMap<String, Double>()

    override fun counted(row: LedgerRow) {
        val cost = row.costUsd ?: return
        val day = Instant.parse(row.at).atZone(zone).toLocalDate()
        add(SpendScope.PERSON, row.person, day, cost)
        row.team?.let { add(SpendScope.TEAM, it, day, cost) }
    }

    /** What [name] has spent today, in USD. */
    fun today(scope: SpendScope, name: String): Double = spent[key(scope, name, today().toString())] ?: 0.0

    /** What [name] has spent this month, in USD. */
    fun month(scope: SpendScope, name: String): Double =
        spent[key(scope, name, YearMonth.from(today()).toString())] ?: 0.0

    private fun add(scope: SpendScope, name: String, day: LocalDate, cost: Double) {
        spent.merge(key(scope, name, day.toString()), cost, Double::plus)
        spent.merge(key(scope, name, YearMonth.from(day).toString()), cost, Double::plus)
    }

    private fun today(): LocalDate = clock.instant().atZone(zone).toLocalDate()

    private fun key(scope: SpendScope, name: String, period: String): String = "${scope.wire}|$name|$period"
}
