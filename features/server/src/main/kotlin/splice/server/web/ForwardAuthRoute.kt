// NEW: server mode (docs/specs/server-mode.md, F13). `GET` / `HEAD /server/forward-auth`: reverse-proxy forward_auth
// endpoint for internal team dashboards (Grafana, Prometheus, etc.) on the same server keys & allowlist. Accepts a
// person's Splice key as `x-api-key`, `Authorization: Bearer`, or `Cookie: splice_forward_auth=<key>`, and returns
// 200 with identity headers (`X-Splice-Person`, `X-Splice-Team`, `X-Splice-Admin`, `X-Auth-Request-Email`) for admins
// and emails/domains matched by `[server.forward_auth] allow`. While the server drains at stop, an admitted key is a 503
// with `Retry-After`, as every keyed request is then (DrainGate).
package splice.server.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import splice.core.auth.BearerScheme
import splice.core.topology.ServerConfig
import splice.core.wire.HttpStatus
import splice.server.KeyVerdict
import splice.server.SERVER_ROOT
import splice.server.ServerKey
import splice.server.ServerKeys
import splice.server.ServerPeople
import splice.server.load.DrainGate
import splice.share.RefusalVoice

internal class ForwardAuthRoute(
    private val server: ServerConfig,
    private val keys: ServerKeys,
    private val people: ServerPeople,
    private val voice: RefusalVoice,
    private val drain: DrainGate,
) {
    private val route = listOf(SERVER_ROOT, FORWARD_AUTH_SEGMENT)

    fun asks(call: ApplicationCall, path: List<String>): Boolean =
        path == route && (call.request.httpMethod == HttpMethod.Get || call.request.httpMethod == HttpMethod.Head)

    suspend fun answer(call: ApplicationCall) {
        val policy = server.forwardAuth
        if (policy == null) {
            voice.refuse(call, HttpStatus.NOT_FOUND, "forward_auth is not configured on this server")
            return
        }
        when (val verdict = keys.admit(presentedKey(call))) {
            is KeyVerdict.Admitted -> respondAdmitted(call, policy, verdict.key)
            is KeyVerdict.Revoked -> voice.refuse(call, HttpStatus.UNAUTHORIZED, "this key was revoked")
            is KeyVerdict.Expired -> voice.refuse(call, HttpStatus.UNAUTHORIZED, "this key expired")
            KeyVerdict.Unknown -> voice.refuse(call, HttpStatus.UNAUTHORIZED, "valid server key required")
        }
    }

    private suspend fun respondAdmitted(
        call: ApplicationCall,
        policy: splice.core.topology.ServerForwardAuth,
        key: ServerKey,
    ) {
        // A key admitted while the server drains is answered as every keyed request then is (#791).
        if (drain.refused(call, voice)) return
        val admin = server.admin(key.person)
        if (!policy.admits(key.person, admin)) {
            voice.refuse(call, HttpStatus.FORBIDDEN, "${key.person} is not allowed to open dashboards here")
            return
        }
        val team = people.team(key.person, server.teams)
        call.response.header("X-Splice-Person", key.person)
        team?.let { call.response.header("X-Splice-Team", it) }
        call.response.header("X-Splice-Admin", admin.toString())
        call.response.header("X-Auth-Request-Email", key.person)
        call.respond(HttpStatusCode.OK)
    }

    private fun presentedKey(call: ApplicationCall): String? {
        val headers = call.request.headers
        val direct = headers[FORWARD_API_KEY_HEADER]?.trim()?.takeIf(String::isNotEmpty)
            ?: BearerScheme.bearerToken(headers[HttpHeaders.Authorization])
        if (direct != null) return direct
        val cookies = headers[HttpHeaders.Cookie] ?: return null
        return cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$FORWARD_AUTH_COOKIE=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }
}

private const val FORWARD_AUTH_SEGMENT = "forward-auth"
private const val FORWARD_AUTH_COOKIE = "splice_forward_auth"
private const val FORWARD_API_KEY_HEADER = "x-api-key"
