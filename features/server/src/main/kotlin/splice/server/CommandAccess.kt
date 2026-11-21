// NEW: server mode (docs/specs/server-mode.md, F12). Which of the served commands a person may use. The operator sets
// a team's with `splice server team set <team> --commands a,b` and a person's with `splice server person set <email>
// --commands a,b|all`. The person's own setting wins over their team's, wider or narrower, and someone neither names
// may use every command. Both are read at each turn, so a change holds from the person's next turn with the keys they
// already hold. A turn on a command the person may not use is refused before it reaches its head (TurnAdmission).
package splice.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import splice.core.topology.ServerTeams
import splice.core.util.LogSafe

/** The commands someone may use: every one the server serves when [all], else those [names] lists, each as
 *  `[server] commands` names it. */
@Serializable
public data class CommandGrant(val all: Boolean = false, val names: List<String> = emptyList()) {
    /** Whether this grant lets its holder use [command], by any name `[server] commands` may call it. */
    internal fun allows(command: ServedCommand): Boolean = all || command.callableAs.any { it in names }

    /** The grant as the CLI and every answer say it: `all`, or the names. */
    internal val said: String get() = if (all) ALL_COMMANDS else names.joinToString(", ")
}

internal class CommandAccess(private val people: ServerPeople, private val teams: ServerTeams) {
    /** Why [person] may not use [command], naming it, or null when they may: their own grant where the operator
     *  set one, else their team's, else every command. */
    fun refusal(person: String, command: ServedCommand): String? {
        val own = people.of(person).commands
        val team = people.team(person, teams)
        val grant = own ?: team?.let { people.ofTeam(it).commands }?.let { CommandGrant(names = it) } ?: return null
        if (grant.allows(command)) return null
        val whose = if (own != null) "their own" else "team ${LogSafe.str(team.orEmpty())}'s"
        return "$person may not use ${command.name} on this server: the commands they may use are ${grant.said} " +
            "($whose setting); ask the server's operator if they need it"
    }
}

/** What a `commands` field of a request read as: a grant, or why it is none. */
internal sealed class GrantReading {
    data class Granted(val grant: CommandGrant) : GrantReading()

    data class Refused(val why: String) : GrantReading()
}

/** Reads a team's or a person's `commands` against the commands this server serves, `[server] commands`. */
internal class CommandNames(private val served: List<String>) {
    private val list = served.joinToString(", ")

    /** [value] as a grant: a list of served commands, or for a person ([everyAllowed]) also "all". */
    fun read(value: JsonElement, everyAllowed: Boolean): GrantReading {
        if (everyAllowed && text(value) == ALL_COMMANDS) return GrantReading.Granted(CommandGrant(all = true))
        val asked = (value as? JsonArray)?.map(::text)
        val names = asked?.filterNotNull()?.filter(String::isNotEmpty)?.distinct()
        val unknown = names?.firstOrNull { it !in served }
        val why = when {
            names == null || null in asked -> {
                val shape = if (everyAllowed) "\"all\" or a list of commands" else "a list of commands"
                "commands must be $shape this server serves ($list)"
            }
            names.isEmpty() -> "name at least one command; this server serves $list"
            unknown != null -> "${LogSafe.str(unknown)} is not a command this server serves; it serves $list"
            else -> return GrantReading.Granted(CommandGrant(names = names))
        }
        return GrantReading.Refused(why)
    }

    private fun text(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
}

/** What a person's `commands` says to let them use every command the server serves. */
internal const val ALL_COMMANDS: String = "all"
