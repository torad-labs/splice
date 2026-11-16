// NEW: server mode (docs/specs/server-mode.md, F5, person-budgets). The budgets' operations, as the CLI asks them
// through the control plane:
//
//   set    {"scope": "person"|"team", "name", "daily_usd"?, "monthly_usd"?, "action"?: "block"|"warn"}
//          sets one budget, keeping the limit it already had where the request names none
//   list   {"budgets": [{"scope", "name", "daily_usd", "monthly_usd", "action", "spent_today_usd",
//          "spent_month_usd", "state": "ok"|"over"}]}
package splice.server.budget

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import splice.core.config.KnobWords
import splice.core.topology.PersonEmails
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.server.ServerAnswer

public class BudgetApi internal constructor(
    private val book: BudgetBook,
    private val gate: BudgetGate,
    private val log: LogSink,
) {
    /** Every budget set, with what was spent against it. */
    public fun list(): ServerAnswer {
        val rows = gate.readings().map(::row)
        val body = buildJsonObject { put("budgets", buildJsonArray { rows.forEach(::add) }) }
        return ServerAnswer.Done(HttpStatusCode.OK, body)
    }

    /** Sets the budget [body] names. */
    public suspend fun set(body: JsonObject?): ServerAnswer = when (val read = read(body)) {
        is BudgetAsk.Unread -> refused(read.why)
        is BudgetAsk.Read -> {
            val set = book.set(read.setting)
            log("[server] budget of ${set.scope} ${LogSafe.str(set.name)} set\n")
            ServerAnswer.Done(HttpStatusCode.OK, row(gate.readings().first { it.setting == set }))
        }
    }

    /** The budget [body] asks for, or why it is none. */
    private fun read(body: JsonObject?): BudgetAsk {
        val scope = SpendScope.entries.firstOrNull { it.wire == JsonScalars.str(body, "scope") }
            ?: return BudgetAsk.Unread("scope must be person or team")
        val name = nameOf(scope, JsonScalars.str(body, "name"))
            ?: return BudgetAsk.Unread(if (scope == SpendScope.PERSON) NAME_NOT_AN_EMAIL else "name must be a team")
        return settingOf(scope, name, body)
    }

    /** The budget of [name] [body] sets, keeping the limits and action it had where [body] names none. */
    private fun settingOf(scope: SpendScope, name: String, body: JsonObject?): BudgetAsk {
        val was = book.of(scope, name)
        val daily = limit(body?.get(DAILY), was?.dailyUsd)
        val monthly = limit(body?.get(MONTHLY), was?.monthlyUsd)
        val action = JsonScalars.str(body, "action") ?: was?.action ?: KnobWords.BUDGET_BLOCK
        return when {
            daily == null || monthly == null -> BudgetAsk.Unread(NOT_A_LIMIT)
            daily.value == null && monthly.value == null -> BudgetAsk.Unread("name a daily_usd, a monthly_usd or both")
            action !in ACTIONS -> BudgetAsk.Unread("action must be block or warn")
            else -> BudgetAsk.Read(BudgetSetting(scope.wire, name, daily.value, monthly.value, action))
        }
    }

    private fun nameOf(scope: SpendScope, name: String?): String? = when (scope) {
        SpendScope.PERSON -> name?.let(PersonEmails::read)
        SpendScope.TEAM -> name?.trim()?.takeIf(String::isNotEmpty)
    }

    /** The limit a request [asked] for, else the one it [had]: wrapped, so a limit the request could not give as a
     *  positive number is null itself. */
    private fun limit(asked: JsonElement?, had: Double?): Limit? {
        if (asked == null) return Limit(had)
        val number = (asked as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it > 0.0 }
        return number?.let(::Limit)
    }

    private fun row(reading: BudgetReading): JsonObject = buildJsonObject {
        val setting = reading.setting
        put("scope", setting.scope)
        put("name", setting.name)
        put(DAILY, setting.dailyUsd)
        put(MONTHLY, setting.monthlyUsd)
        put("action", setting.action)
        put("spent_today_usd", reading.spentToday)
        put("spent_month_usd", reading.spentMonth)
        put("state", if (reading.over) "over" else "ok")
    }

    private fun refused(why: String): ServerAnswer = ServerAnswer.Refused(HttpStatusCode.BadRequest, why)

    /** A limit as a request leaves it: [value] USD, or none. */
    private data class Limit(val value: Double?)

    /** A budget as a request asked for it, or why it is none. */
    private sealed class BudgetAsk {
        data class Read(val setting: BudgetSetting) : BudgetAsk()

        data class Unread(val why: String) : BudgetAsk()
    }
}

private const val DAILY = "daily_usd"
private const val MONTHLY = "monthly_usd"
private val ACTIONS = setOf(KnobWords.BUDGET_BLOCK, KnobWords.BUDGET_WARN)
private const val NOT_A_LIMIT = "daily_usd and monthly_usd must each be a positive number"
private const val NAME_NOT_AN_EMAIL = "name must be an email, as in ada@team.example"
