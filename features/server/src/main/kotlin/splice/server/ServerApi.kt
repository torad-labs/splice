// NEW: server mode (docs/specs/server-mode.md, F1, F3 and F4). The server's own operations on its keys, as
// each of its three doors asks them: the CLI through the control plane, the control plane itself, and an admin's key
// on the listener. One class per noun (a person's settings are PersonApi, a team's TeamApi), so a key is validated,
// minted, listed and revoked the same way whichever door asked, and each door only says the answer in its own shape.
package splice.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.topology.PersonEmails
import splice.core.topology.ServerDurations
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.time.Duration
import java.time.Instant

/** What an operation answers: the JSON to send, or why it is refused. */
public sealed class ServerAnswer {
    public data class Done(val status: HttpStatusCode, val body: JsonObject) : ServerAnswer()

    public data class Refused(val status: HttpStatusCode, val why: String) : ServerAnswer()

    /** A body that is no JSON, such as a CSV report, of [contentType]. */
    public data class Text(val status: HttpStatusCode, val contentType: String, val text: String) : ServerAnswer()
}

public class KeyApi(private val keys: ServerKeys, private val log: LogSink) {
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
        val lifetime = expires?.let(ServerDurations::read)
        return when {
            person == null -> notAnEmail()
            expires != null && lifetime == null -> refused("expires must be a duration such as 12h, 30d or 8w")
            name != null && !nameShape.matches(name) -> refused("name must be one line of at most $NAME_MAX characters")
            else -> minted(KeyRequest(person, name, lifetime), HttpStatusCode.Created)
        }
    }

    /** [person]'s own key after their sign-in (F2): minted for [device] with the source `signin`, living [days],
     *  and answered with the key, once. */
    internal suspend fun signedIn(person: String, device: String, days: Int): ServerAnswer {
        val lifetime = Duration.ofDays(days.toLong()).toMillis()
        val request = KeyRequest(person, lifetimeMs = lifetime, source = SIGNIN_SOURCE, device = device)
        return minted(request, HttpStatusCode.OK)
    }

    private suspend fun minted(request: KeyRequest, status: HttpStatusCode): ServerAnswer {
        val minted = keys.mint(request)
        val how = request.device?.let { " by a sign-in on the device ${LogSafe.str(it)}" }.orEmpty()
        log("[server] key ${minted.row.prefix} issued to ${LogSafe.str(request.person)}$how\n")
        val answer = buildJsonObject {
            put("key", minted.reveal())
            row(minted.row).forEach { (field, value) -> put(field, value) }
        }
        return ServerAnswer.Done(status, answer)
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
        put("device", key.device)
        put("created_at", at(key.createdAt))
        put("expires_at", key.expiresAt?.let(::at))
        put("last_used_at", key.lastUsedAt?.let(::at))
        put("revoked_at", key.revokedAt?.let(::at))
        put("superseded_by", key.supersededBy)
    }

    private fun at(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun done(body: JsonObject): ServerAnswer = ServerAnswer.Done(HttpStatusCode.OK, body)

    private fun refused(why: String): ServerAnswer = ServerAnswer.Refused(HttpStatusCode.BadRequest, why)

    private fun notAnEmail(): ServerAnswer = refused("person must be an email, as in ada@team.example")
}

// why: a key's name is a label an operator reads in a list ("laptop", "ci"); 64 characters hold any such label.
private const val NAME_MAX = 64

// The field every answer names a person in.
// A person, as every server answer and query names one.
internal const val PERSON = "person"
