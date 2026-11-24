// NEW: server mode (docs/specs/server-mode.md, "Mode routes"). `/server/mod/<name>/...` on a team server's listener: a
// request a key was admitted for, handed to the mode named <name> as who is asking (the key's person, their team,
// whether they are an admin) and never as the key. Only a few headers go with it (modHeaders), so a credential the
// caller sent never reaches a mode, and its body is read under a cap. Refusals are the listener's own, in the
// Anthropic shape: 404 for a mode that does not serve here, 403 for an admins-only mode and a member's key.
package splice.server.mod

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.server.ModAnswer
import splice.core.server.ModCaller
import splice.core.server.ModRequest
import splice.core.server.ModResponse
import splice.core.server.ServerModRoutes
import splice.core.server.modHeaders
import splice.core.topology.ServerTeams
import splice.core.wire.HttpStatus
import splice.server.ServerPeople
import splice.server.caller.AdminAccess
import splice.share.CappedBodies
import splice.share.RefusalVoice

/** The modes the listener hands `/server/mod/...` to ([routes]; null when the daemon offers none), read for
 *  [people]'s [teams] and [adminAccess] policy, refusing in [voice]. A mode's own code runs on [io]. */
internal class ModRoute(
    private val routes: ServerModRoutes?,
    private val teams: ServerTeams,
    private val people: ServerPeople,
    private val adminAccess: AdminAccess,
    private val voice: RefusalVoice,
    private val io: CoroutineDispatcher,
) {
    private val bodies = CappedBodies(voice)

    /** [rest], the path after `/server/mod`, asked for [person], whose key was admitted. */
    suspend fun answer(call: ApplicationCall, person: String, rest: List<String>) {
        val name = rest.firstOrNull()
        if (routes == null || name == null) return noSuchMod(call, name)
        val request = request(call, person, rest.drop(1)) ?: return
        said(call, name, person, withContext(io) { routes.answer(name, request) })
    }

    /** [answer], mode [name]'s to [person], as the listener says it: the mode's own response, or a refusal. */
    private suspend fun said(call: ApplicationCall, name: String, person: String, answer: ModAnswer) {
        when (answer) {
            ModAnswer.NoSuchMod -> noSuchMod(call, name)
            ModAnswer.AdminsOnly -> {
                val why = "mode $name on this server is for its admins, and $person is not one of them"
                voice.refuse(call, HttpStatus.FORBIDDEN, why)
            }
            is ModAnswer.Failed -> voice.refuse(call, HttpStatus.INTERNAL_SERVER_ERROR, answer.said)
            is ModAnswer.Answered -> answer.response?.let { respond(call, it) }
                ?: voice.refuse(call, HttpStatus.NOT_FOUND, "mode $name has no such path")
        }
    }

    /** What a mode is handed of [call] for [person], [path] after its name; null once a body too large or too slow
     *  was refused. */
    private suspend fun request(call: ApplicationCall, person: String, path: List<String>): ModRequest? {
        val body = bodies.read(call, MOD_BODY_BYTES, MOD_BODY_READ_MS) ?: return null
        return ModRequest(
            method = call.request.httpMethod.value,
            path = path,
            caller = ModCaller(person, people.team(person, teams), adminAccess.allows(call, person)),
            headers = modHeaders.mapNotNull { header -> call.request.headers[header]?.let { header to it } }.toMap(),
            query = call.request.queryParameters.entries().associate { (key, values) -> key to values.first() },
            body = body,
        )
    }

    private suspend fun respond(call: ApplicationCall, response: ModResponse) {
        val type = typeOf(response.contentType)
        call.respondBytes(response.body, type, HttpStatusCode.fromValue(response.status))
    }

    /** [text] as a content type; a mode that wrote one Ktor cannot read answers as bytes. */
    private fun typeOf(text: String): ContentType = try {
        ContentType.parse(text)
    } catch (_: BadContentTypeFormatException) {
        ContentType.Application.OctetStream
    }

    private suspend fun noSuchMod(call: ApplicationCall, name: String?) {
        val what = name?.let { "no mode named '$it' serves this server" } ?: "name a mode: /server/mod/<name>/..."
        voice.refuse(call, HttpStatus.NOT_FOUND, what)
    }
}

// why: a mode's request is a form or a small JSON document, never a turn; 1 MiB is far past any of those and keeps one
// key holder from making the listener hold a large body in memory for a mode.
private const val MOD_BODY_BYTES = 1024 * 1024

// why: the same thirty seconds the telemetry relay gives a body still arriving, so a slow link is not cut short and a
// stalled one does not hold a connection open for long.
private const val MOD_BODY_READ_MS = 30_000L
