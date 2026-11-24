package splice.server.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import splice.core.topology.ServerForwardAuth
import splice.core.util.FormEncoding
import splice.core.wire.HttpStatus
import splice.server.BROWSER_SOURCE
import splice.server.KeyRequest
import splice.server.SERVER_ROOT
import splice.server.ServerKey
import splice.server.ServerKeys
import splice.server.caller.AdminAccess
import splice.server.signin.IdVerdict
import splice.server.signin.SigninAttempts
import splice.server.signin.SigninParts
import splice.server.signin.SigninRedirects
import splice.server.signin.signinScopes
import splice.share.Asked
import splice.share.RefusalVoice
import java.time.Clock
import java.time.Duration

/** Optional OIDC flow for a browser on the dashboard's configured HTTPS origin. */
internal class BrowserForwardAuth(
    private val policy: ServerForwardAuth,
    private val signin: SigninParts,
    private val keys: ServerKeys,
    private val access: AdminAccess,
    private val attempts: SigninAttempts,
    private val voice: RefusalVoice,
    clock: Clock,
) {
    private val cookies = BrowserAuthCookies(clock)
    private val origin = checkNotNull(policy.browserOrigin).trimEnd('/')
    private val callback = "$origin/$SERVER_ROOT/$FORWARD_AUTH_SEGMENT/$CALLBACK"

    fun asks(path: List<String>): Boolean =
        path == forwardAuthPath + LOGIN || path == forwardAuthPath + CALLBACK

    fun session(call: ApplicationCall): ServerKey? = cookies.reference(call)?.let(keys::browserSession)

    suspend fun answer(call: ApplicationCall, path: List<String>) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        when {
            call.request.httpMethod != HttpMethod.Get ->
                voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on this server")
            path.last() == LOGIN -> begin(call)
            else -> complete(call)
        }
    }

    private suspend fun begin(call: ApplicationCall) {
        val asked = call.request.queryParameters["return_to"] ?: "/"
        val returnTo = SigninRedirects.dashboard(asked)
            ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, "return_to must be a path on this dashboard")
        val endpoint = when (val doc = signin.issuer.doc()) {
            is Asked.Refused -> return voice.refuse(call, HttpStatus.BAD_GATEWAY, doc.why)
            is Asked.Had -> doc.value.authorizationEndpoint
        }
        val flow = cookies.flow(returnTo)
        cookies.start(call, flow)
        val query = FormEncoding.formEncode(
            "response_type" to "code",
            "client_id" to signin.signin.clientId,
            "redirect_uri" to callback,
            "scope" to signinScopes.joinToString(" "),
            "state" to flow.state,
            "nonce" to flow.nonce,
            "code_challenge" to cookies.pkce(flow).challenge,
            "code_challenge_method" to "S256",
        )
        val join = if ('?' in endpoint) "&" else "?"
        redirect(call, "$endpoint$join$query")
    }

    private suspend fun complete(call: ApplicationCall) {
        val flow = cookies.pending(call)
        cookies.finish(call)
        val query = call.request.queryParameters
        val code = query.getAll("code")?.singleOrNull()?.takeIf(String::isNotBlank)
        val matched = flow?.takeIf { query.getAll("state")?.singleOrNull() == it.state }
        if (matched == null || code == null) {
            return voice.refuse(call, HttpStatus.UNAUTHORIZED, "browser sign-in is missing, expired or invalid")
        }
        val address = attempts.address(call)
        if (!attempts.admit(call, address, signin.signin.ratePerMinute, voice)) return
        when (val verified = signin.verify(code, matched.verifier, callback, matched.nonce)) {
            is IdVerdict.Refused -> voice.refuse(call, HttpStatus.UNAUTHORIZED, "sign-in refused: ${verified.why}")
            is IdVerdict.Person -> signedIn(call, verified.email, matched.returnTo)
        }
    }

    private suspend fun signedIn(call: ApplicationCall, email: String, returnTo: String) {
        if (!policy.admits(email, access.allows(call, email))) {
            return voice.refuse(call, HttpStatus.FORBIDDEN, "this account is not allowed to open dashboards here")
        }
        val lifetime = Duration.ofSeconds(policy.sessionSeconds.toLong()).toMillis()
        val row = keys.mint(KeyRequest(email, lifetimeMs = lifetime, source = BROWSER_SOURCE)).row
        cookies.session(call, row.hash, policy.sessionSeconds)
        redirect(call, origin + returnTo)
    }

    private suspend fun redirect(call: ApplicationCall, target: String) {
        call.response.headers.append(HttpHeaders.Location, target)
        call.respond(HttpStatusCode.SeeOther)
    }
}

private const val LOGIN = "login"
private const val CALLBACK = "callback"
