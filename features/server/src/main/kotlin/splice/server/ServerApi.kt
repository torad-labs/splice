// NEW: server mode (docs/specs/server-mode.md, F1 and F3). The server's own operations on its keys and its people, as
// each of its three doors asks them: the CLI through the control plane, the control plane itself, and an admin's key
// on the listener. One class per noun, so a key is validated, minted, listed and revoked the same way whichever door
// asked, and each door only says the answer in its own shape.
package splice.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import splice.core.topology.PersonEmails
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What an operation answers: the JSON to send, or why it is refused. */
public sealed class ServerAnswer {
    public data class Done(val status: HttpStatusCode, val body: JsonObject) : ServerAnswer()

    public data class Refused(val status: HttpStatusCode, val why: String) : ServerAnswer()
}

public class KeyApi(private val keys: ServerKeys, private val log: LogSink) {
    private val lifetimes = KeyLifetimes()
    private val nameShape = Regex("""^[^\p{Cntrl}]{1,$NAME_MAX}$""")

    /** Every key, or only [person]'s when one is named: `{"keys": [...]}`, no secret in it. */
    public fun list(person: String?): ServerAnswer {
        val who = person?.let { PersonEmails.read(it) ?: return notAnEmail() }
        val rows = keys.list(who)
        return done(buildJsonObject { put("keys", buildJsonArray { rows.forEach { add(row(it)) } }) })
    }

    /** Mints a key from `{"person": email, "name"?: text, "expires"?: "30d"}` and answers it with the key, once. */
    public suspend fun create(body: JsonObject?): ServerAnswer {
        val person = JsonScalars.str(body, PERSON)?.let(PersonEmails::read)
        val name = JsonScalars.str(body, "name")?.trim()?.takeIf(String::isNotEmpty)
        val expires = JsonScalars.str(body, "expires")
        val lifetime = expires?.let(lifetimes::read)
        return when {
            person == null -> notAnEmail()
            expires != null && lifetime == null -> refused("expires must be a duration such as 12h, 30d or 8w")
            name != null && !nameShape.matches(name) -> refused("name must be one line of at most $NAME_MAX characters")
            else -> minted(KeyRequest(person, name, lifetime))
        }
    }

    private suspend fun minted(request: KeyRequest): ServerAnswer {
        val minted = keys.mint(request)
        log("[server] key ${minted.row.prefix} issued to ${LogSafe.str(request.person)}\n")
        val answer = buildJsonObject {
            put("key", minted.reveal())
            row(minted.row).forEach { (field, value) -> put(field, value) }
        }
        return ServerAnswer.Done(HttpStatusCode.Created, answer)
    }

    /** Revokes the key [prefix] names; 404 when no key has that prefix. */
    public suspend fun revoke(prefix: String): ServerAnswer {
        val key = keys.revoke(prefix)
            ?: return ServerAnswer.Refused(HttpStatusCode.NotFound, "no key has the prefix ${LogSafe.str(prefix)}")
        log("[server] key ${key.prefix} of ${LogSafe.str(key.person)} revoked\n")
        return done(row(key))
    }

    /** Offboarding: revokes every live key of [person] and answers how many, `{"person", "revoked": N}`. */
    public suspend fun revokePerson(person: String): ServerAnswer {
        val who = PersonEmails.read(person) ?: return notAnEmail()
        val count = keys.revokePerson(who)
        log("[server] $count keys of ${LogSafe.str(who)} revoked\n")
        return done(
            buildJsonObject {
                put(PERSON, who)
                put("revoked", count)
            },
        )
    }

    private fun row(key: ServerKey): JsonObject = buildJsonObject {
        put("prefix", key.prefix)
        put(PERSON, key.person)
        put("name", key.name)
        put("source", key.source)
        put("created_at", at(key.createdAt))
        put("expires_at", key.expiresAt?.let(::at))
        put("last_used_at", key.lastUsedAt?.let(::at))
        put("revoked_at", key.revokedAt?.let(::at))
    }

    private fun at(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun done(body: JsonObject): ServerAnswer = ServerAnswer.Done(HttpStatusCode.OK, body)

    private fun refused(why: String): ServerAnswer = ServerAnswer.Refused(HttpStatusCode.BadRequest, why)

    private fun notAnEmail(): ServerAnswer = refused("person must be an email, as in ada@team.example")
}

public class PersonApi(private val people: ServerPeople, private val log: LogSink) {

    /** Sets [person]'s own caps from `{"inflight"?: N, "rpm"?: N}`, each 0 (no cap) or more, and answers their
     *  settings as they now stand. */
    public suspend fun set(person: String, body: JsonObject?): ServerAnswer {
        val who = PersonEmails.read(person)
            ?: return refused("the person must be an email, as in ada@team.example")
        val inflight = body?.get("inflight")
        val rpm = body?.get("rpm")
        val change = PersonSettings(cap(inflight), cap(rpm))
        return when {
            inflight == null && rpm == null -> refused("name at least one setting: inflight or rpm")
            (inflight != null && change.inflight == null) || (rpm != null && change.rpm == null) ->
                refused("inflight and rpm must each be a whole number, 0 (no cap) or more")
            else -> {
                val now = people.set(who, change)
                log("[server] settings of ${LogSafe.str(who)} changed\n")
                ServerAnswer.Done(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put(PERSON, who)
                        put("inflight", now.inflight)
                        put("rpm", now.rpm)
                    },
                )
            }
        }
    }

    private fun cap(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }

    private fun refused(why: String): ServerAnswer = ServerAnswer.Refused(HttpStatusCode.BadRequest, why)
}

/** Reads how long a key lives: a whole number and a unit, `90s`, `45m`, `12h`, `30d`, `8w`. */
internal class KeyLifetimes {
    private val shape = Regex("""^(\d{1,9})([smhdw])$""")
    private val units = mapOf(
        's' to ChronoUnit.SECONDS,
        'm' to ChronoUnit.MINUTES,
        'h' to ChronoUnit.HOURS,
        'd' to ChronoUnit.DAYS,
        'w' to ChronoUnit.WEEKS,
    )

    /** [text] in milliseconds, or null when it is no such duration or is zero. */
    fun read(text: String): Long? {
        val found = shape.matchEntire(text.trim().lowercase()) ?: return null
        val unit = units.getValue(found.groupValues[2].single()).duration
        return unit.multipliedBy(found.groupValues[1].toLong()).toMillis().takeIf { it > 0 }
    }
}

// why: a key's name is a label an operator reads in a list ("laptop", "ci"); 64 characters hold any such label.
private const val NAME_MAX = 64

// The field every answer names a person in.
private const val PERSON = "person"
