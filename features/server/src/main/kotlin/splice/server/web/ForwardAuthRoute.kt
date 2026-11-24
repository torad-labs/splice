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
import splice.core.topology.ServerForwardAuth
import splice.core.wire.HttpStatus
import splice.server.KeyVerdict
import splice.server.SERVER_ROOT
import splice.server.ServerKey
import splice.server.ServerKeys
import splice.server.ServerPeople
import splice.server.caller.AdminAccess
import splice.share.RefusalVoice

/** Reverse-proxy check for keys and, when enabled, signed dashboard sessions. */
internal class ForwardAuthRoute(
    private val server: ServerConfig,
    private val keys: ServerKeys,
    private val people: ServerPeople,
    private val access: AdminAccess,
    private val browser: BrowserForwardAuth?,
    private val voice: RefusalVoice,
) {
    fun asks(call: ApplicationCall, path: List<String>): Boolean =
        browser?.asks(path) == true || (path == forwardAuthPath && call.request.httpMethod in checkMethods)

    suspend fun answer(call: ApplicationCall, path: List<String>) {
        if (browser != null && browser.asks(path)) return browser.answer(call, path)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val policy = server.forwardAuth
            ?: return voice.refuse(call, HttpStatus.NOT_FOUND, "forward_auth is not configured on this server")
        when (val verdict = presented(call)) {
            is KeyVerdict.Admitted -> respondAdmitted(call, policy, verdict.key)
            is KeyVerdict.Revoked -> voice.refuse(call, HttpStatus.UNAUTHORIZED, "this key was revoked")
            is KeyVerdict.Expired -> voice.refuse(call, HttpStatus.UNAUTHORIZED, "this key expired")
            KeyVerdict.Unknown -> voice.refuse(call, HttpStatus.UNAUTHORIZED, "valid server key or session required")
        }
    }

    private suspend fun respondAdmitted(call: ApplicationCall, policy: ServerForwardAuth, key: ServerKey) {
        val admin = access.allows(call, key.person)
        if (!policy.admits(key.person, admin)) {
            voice.refuse(call, HttpStatus.FORBIDDEN, "${key.person} is not allowed to open dashboards here")
            return
        }
        val team = people.team(key.person, server.teams)
        call.response.header("X-Splice-Person", key.person)
        team?.let { call.response.header("X-Splice-Team", it) }
        call.response.header("X-Splice-Admin", admin.toString())
        call.response.header(policy.emailHeader, key.person)
        policy.roleHeader?.let { call.response.header(it, if (admin) "admin" else "user") }
        call.respond(HttpStatusCode.OK)
    }

    private suspend fun presented(call: ApplicationCall): KeyVerdict {
        val headers = call.request.headers
        val direct = headers[FORWARD_API_KEY_HEADER]?.trim()?.takeIf(String::isNotEmpty)
            ?: BearerScheme.bearerToken(headers[HttpHeaders.Authorization])
        return when {
            direct != null -> keys.admit(direct)
            browser == null -> keys.admit(call.request.cookies[LEGACY_COOKIE])
            else -> browser.session(call)?.let { KeyVerdict.Admitted(it) } ?: KeyVerdict.Unknown
        }
    }
}

internal const val FORWARD_AUTH_SEGMENT = "forward-auth"
internal val forwardAuthPath = listOf(SERVER_ROOT, FORWARD_AUTH_SEGMENT)
private val checkMethods = setOf(HttpMethod.Get, HttpMethod.Head)
private const val LEGACY_COOKIE = "splice_forward_auth"
private const val FORWARD_API_KEY_HEADER = "x-api-key"
