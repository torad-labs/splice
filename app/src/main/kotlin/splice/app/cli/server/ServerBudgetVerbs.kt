// NEW: server mode (docs/specs/server-mode.md, F5, person-budgets): `splice server budget set|list`, the operator's
// budgets for a person or a team, priced at the models' rate cards:
//
//   budget set --person <email>|--team <name> [--daily-usd N] [--monthly-usd N] [--action block|warn]
//                       sets one, keeping the limit and action it already had where a flag is not given
//   budget list [--json] every budget, with what was spent against it today and this month, and whether it is over
package splice.app.cli.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.JSON_SWITCH
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerAsk

internal class ServerBudgetVerbs(private val ask: PeerAsk) {
    private val lines = BudgetLines()

    /** `budget set --person <email>|--team <name> [--daily-usd N] [--monthly-usd N] [--action block|warn]` */
    fun set(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(PERSON_FLAG, TEAM_FLAG) + SETTINGS.keys, emptySet()).parse(args)
            ?.takeIf { it.words.isEmpty() } ?: return null
        val body = bodyOf(parsed.values) ?: return null
        return ask.managed(ControlRequest("PUT", BUDGETS_PATH, body)) { lines.budget(it) }
    }

    /** `budget list [--json]` */
    fun list(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf(JSON_SWITCH)).parse(args)?.takeIf { it.words.isEmpty() }
            ?: return null
        return ask.managed(ControlRequest("GET", BUDGETS_PATH), JSON_SWITCH in parsed.switches) { lines.budgets(it) }
    }

    /** `{"scope", "name", "daily_usd"?, "monthly_usd"?, "action"?}` from [flags]; null unless exactly one of `--person`
     *  and `--team` is given. A limit that is no number is sent as written, so the daemon says what is wrong with it. */
    private fun bodyOf(flags: Map<String, String>): JsonObject? {
        val person = flags[PERSON_FLAG]
        val team = flags[TEAM_FLAG]
        val (scope, name) = when {
            person != null && team == null -> "person" to person
            team != null && person == null -> "team" to team
            else -> return null
        }
        return buildJsonObject {
            put("scope", scope)
            put("name", name)
            SETTINGS.forEach { (flag, field) ->
                val text = flags[flag] ?: return@forEach
                val number = text.toDoubleOrNull().takeIf { field != ACTION_FIELD }
                put(field, number?.let(::JsonPrimitive) ?: JsonPrimitive(text))
            }
        }
    }
}

private const val BUDGETS_PATH = "/api/server/budgets"
private const val ACTION_FIELD = "action"

// Each setting's flag, by the field of the daemon's request it fills.
private val SETTINGS = mapOf("--daily-usd" to "daily_usd", "--monthly-usd" to "monthly_usd", "--action" to ACTION_FIELD)
