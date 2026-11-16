// NEW: server mode (docs/specs/server-mode.md, F1 "Admin over the listener"). The server's own API on its listener,
// under `/server/api/`, for a key whose person is in `[server] admins`: the same key operations the control plane
// offers on loopback, reachable from wherever the admin is. Anyone else's key is 403.
//
//   GET    /server/api/keys[?person=]   every key, or one person's
//   POST   /server/api/keys             mint one: {"person", "name"?, "expires"?}
//   DELETE /server/api/keys/<prefix>    revoke one
//   GET    /server/api/usage            the usage report (F5): by, since or from and to, format, as UsageApi reads them
package splice.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import splice.core.topology.ServerConfig
import splice.core.wire.HttpStatus
import splice.http.JsonBody
import splice.server.ledger.UsageApi
import splice.share.RefusalVoice

internal class ServerAdmin(
    private val server: ServerConfig,
    private val keys: KeyApi,
    private val usage: UsageApi,
    private val voice: RefusalVoice,
) {
    private val jsonBody = JsonBody()

    /** [rest], the path after `/server`, asked with [key]. */
    suspend fun answer(call: ApplicationCall, key: ServerKey, rest: List<String>) {
        when {
            rest.firstOrNull() != API_ROUTE -> noSuchPath(call)
            !server.admin(key.person) -> {
                val why = "the server's API is for its admins, and ${key.person} is not one of them"
                voice.refuse(call, HttpStatus.FORBIDDEN, why)
            }
            else -> say(call, asked(call, rest.drop(1)))
        }
    }

    /** What an admin's request for [path], the path after `/server/api`, comes to; null for a path that is none. */
    private suspend fun asked(call: ApplicationCall, path: List<String>): ServerAnswer? {
        val prefix = path.getOrNull(1)
        return when {
            path == listOf(USAGE_ROUTE) && call.request.httpMethod == HttpMethod.Get ->
                usage.report(call.request.queryParameters)
            path.firstOrNull() != KEYS_ROUTE || path.size > 2 -> null
            prefix == null -> everyKey(call)
            call.request.httpMethod == HttpMethod.Delete -> keys.revoke(prefix)
            else -> null
        }
    }

    /** `/server/api/keys` itself: the list, or a new key. */
    private suspend fun everyKey(call: ApplicationCall): ServerAnswer? = when (call.request.httpMethod) {
        HttpMethod.Get -> keys.list(call.request.queryParameters["person"])
        HttpMethod.Post -> keys.create(jsonBody.parse(call))
        else -> null
    }

    private suspend fun say(call: ApplicationCall, answer: ServerAnswer?) {
        when (answer) {
            null -> noSuchPath(call)
            is ServerAnswer.Done -> {
                call.respondText(answer.body.toString(), ContentType.Application.Json, answer.status)
            }
            is ServerAnswer.Refused -> voice.refuse(call, answer.status.value, answer.why)
            is ServerAnswer.Text -> call.respondText(answer.text, ContentType.parse(answer.contentType), answer.status)
        }
    }

    private suspend fun noSuchPath(call: ApplicationCall) {
        voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on this server")
    }
}

private const val API_ROUTE = "api"
private const val KEYS_ROUTE = "keys"
private const val USAGE_ROUTE = "usage"
