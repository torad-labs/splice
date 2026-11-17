// NEW: server mode (docs/specs/server-mode.md, F3, F4, F5 and F12). The server's operation on one person's own
// settings, as the CLI asks it through the control plane: their caps (in flight, a minute's turns and a minute's
// tokens), the upstream they prefer, the team they are on, and the commands they may use. Each is validated here,
// whichever door asked, and holds from the person's next turn.
package splice.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import splice.core.topology.PersonEmails
import splice.core.util.LogSafe
import splice.core.util.LogSink

public class PersonApi(
    private val people: ServerPeople,
    private val log: LogSink,
    private val upstreams: UpstreamApi,
    /** `[server] commands`, the commands a person may be let use (F12). */
    served: List<String>,
) {
    private val commandNames = CommandNames(served)

    /** Sets what `{"inflight"?: N, "rpm"?: N, "tpm"?: N, "upstream"?: name, "team"?: name, "commands"?: "all"|[names]}`
     *  names for [person] and answers their settings as they now stand. An in-flight or per-minute cap is 0 (no cap) or
     *  more, and a token cap a whole number above 0; an upstream is one this server's providers list, or "" for no
     *  preference; a team is any name, or "" for none of their own; commands are "all" or some of the commands this
     *  server serves. */
    public suspend fun set(person: String, body: JsonObject?): ServerAnswer {
        val who = PersonEmails.read(person)
            ?: return refused("the person must be an email, as in ada@team.example")
        val grant = body?.get(COMMANDS_FIELD)?.let { commandNames.read(it, everyAllowed = true) }
        val change = PersonSettings(
            cap(body?.get(INFLIGHT_FIELD)),
            cap(body?.get(RPM_FIELD)),
            cap(body?.get(TPM_FIELD))?.takeIf { it > 0 },
            name(body?.get(UPSTREAM_FIELD)),
            name(body?.get(TEAM_FIELD)),
            (grant as? GrantReading.Granted)?.grant,
        )
        val why = (grant as? GrantReading.Refused)?.why ?: whyNot(body, change)
        why?.let { return refused(it) }
        val now = people.set(who, change)
        log("[server] settings of ${LogSafe.str(who)} changed\n")
        return ServerAnswer.Done(
            HttpStatusCode.OK,
            buildJsonObject {
                put(PERSON, who)
                put(INFLIGHT_FIELD, now.inflight)
                put(RPM_FIELD, now.rpm)
                put(TPM_FIELD, now.tpm)
                put(UPSTREAM_FIELD, now.preferred)
                put(TEAM_FIELD, now.ownTeam)
                put(COMMANDS_FIELD, now.commands?.let(::said) ?: JsonNull)
            },
        )
    }

    /** Why [body] cannot set [change], as it read: nothing named, a cap or a team that is none, or no such upstream;
     *  null when it can. */
    private fun whyNot(body: JsonObject?, change: PersonSettings): String? {
        val inflight = body?.get(INFLIGHT_FIELD)
        val rpm = body?.get(RPM_FIELD)
        val tpm = body?.get(TPM_FIELD)
        val upstream = body?.get(UPSTREAM_FIELD)
        val team = body?.get(TEAM_FIELD)
        return when {
            listOfNotNull(inflight, rpm, tpm, upstream, team, body?.get(COMMANDS_FIELD)).isEmpty() ->
                "name at least one setting: inflight, rpm, tpm, upstream, team or commands"
            unread(inflight, change.inflight) || unread(rpm, change.rpm) ->
                "inflight and rpm must each be a whole number, 0 (no cap) or more"
            unread(tpm, change.tpm) -> "tpm must be a whole number of tokens a minute, 1 or more"
            team != null && change.team == null -> "team must be a name, or \"\" for none of their own"
            upstream != null -> notAnUpstream(change.upstream)
            else -> null
        }
    }

    /** Whether a cap was [asked] and could not be [read] as one. */
    private fun unread(asked: JsonElement?, read: Int?): Boolean = asked != null && read == null

    private fun cap(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }

    private fun name(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    /** [grant] as an answer says it: "all", or the list of commands. */
    private fun said(grant: CommandGrant): JsonElement =
        if (grant.all) JsonPrimitive(ALL_COMMANDS) else JsonArray(grant.names.map(::JsonPrimitive))

    /** Why [name] is no upstream a person can prefer, or null when it is one, or "" (no preference). */
    private fun notAnUpstream(name: String?): String? {
        val known = upstreams.names()
        return when {
            name == null -> "upstream must be the name of an upstream, or \"\" for no preference"
            name.isEmpty() || name in known -> null
            known.isEmpty() -> "no provider of this server lists upstreams, so there is none to prefer"
            else -> "no upstream is named ${LogSafe.str(name)}; this server's are ${known.joinToString(", ")}"
        }
    }

    private fun refused(why: String): ServerAnswer = ServerAnswer.Refused(HttpStatusCode.BadRequest, why)
}

// The field a person's preferred upstream is set and answered in.
private const val UPSTREAM_FIELD = "upstream"

// The field a person's own team is set and answered in.
private const val TEAM_FIELD = "team"

// The fields a person's own caps are set and answered in.
private const val INFLIGHT_FIELD = "inflight"
private const val RPM_FIELD = "rpm"
private const val TPM_FIELD = "tpm"

/** The field a team's and a person's commands are set and answered in (F12). */
internal const val COMMANDS_FIELD: String = "commands"
