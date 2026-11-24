// NEW: server mode (docs/specs/server-mode.md, F2). The two routes of the listener that answer without a key, since
// they are how a person gets one:
//
//   GET  /server/signin         everything a laptop needs to send its person to the company's identity provider: the
//                               issuer, the client id, the authorization endpoint, the scopes and the domains admitted.
//                               Never the client secret, which only the server holds.
//   POST /server/signin/token   {code, code_verifier, redirect_uri, nonce, device}: the code the provider sent the
//                               laptop's loopback, traded here with its PKCE verifier; the ID token checked (IdTokens);
//                               and the person a key minted, bound to the device, living `key_days`. Every failure is
//                               a 401 `authentication_error` saying why, and mints nothing.
//
// The trade is held to `rate_per_minute` per client address (its trusted-proxy reading, ClientAddresses): one past it
// is a 429 with `Retry-After`, before the provider is asked anything. A server with no `[server.signin]` answers both
// routes 404, saying a key comes from the operator there.
package splice.server.signin

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.topology.ServerSignin
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.JsonBody
import splice.server.KeyApi
import splice.server.SERVER_ROOT
import splice.server.ServerAnswer
import splice.share.Asked
import splice.share.RefusalVoice

/** What a sign-in is checked and minted with, on a server whose `[server.signin]` is set. */
internal data class SigninParts(val signin: ServerSignin, val issuer: SigninIssuer, val tokens: IdTokens) {
    suspend fun verify(code: String, verifier: String, redirect: String, nonce: String): IdVerdict =
        when (val traded = issuer.trade(code, verifier, redirect)) {
            is Asked.Refused -> IdVerdict.Refused(traded.why)
            is Asked.Had -> tokens.person(traded.value, nonce)
        }
}

/** A trade's fields, as the laptop sends them. */
@Serializable
private data class Trade(
    val code: String,
    @SerialName("code_verifier") val verifier: String,
    @SerialName("redirect_uri") val redirect: String,
    val nonce: String,
    val device: String,
)

internal class SigninRoute(
    private val parts: SigninParts?,
    private val keys: KeyApi,
    private val attempts: SigninAttempts,
    private val voice: RefusalVoice,
    private val log: LogSink,
) {
    private val jsonBody = JsonBody()
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceShape = Regex("""^[^\p{Cntrl}]{1,$DEVICE_MAX}$""")

    /** Whether [path] is one of the sign-in routes, which answer without a key. */
    fun asks(path: List<String>?): Boolean = path == CONFIG_PATH || path == TOKEN_PATH

    suspend fun answer(call: ApplicationCall, path: List<String>) {
        val signin = parts
        val method = call.request.httpMethod
        when {
            signin == null -> voice.refuse(call, HttpStatus.NOT_FOUND, NO_SIGNIN)
            path == CONFIG_PATH && method == HttpMethod.Get -> config(call, signin)
            path == TOKEN_PATH && method == HttpMethod.Post -> token(call, signin)
            else -> voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on this server")
        }
    }

    /** The sign-in config a laptop starts from. */
    private suspend fun config(call: ApplicationCall, parts: SigninParts) {
        val doc = when (val found = parts.issuer.doc()) {
            is Asked.Refused -> return voice.refuse(call, HttpStatus.BAD_GATEWAY, found.why)
            is Asked.Had -> found.value
        }
        val body = buildJsonObject {
            put("issuer", parts.signin.issuer)
            put("client_id", parts.signin.clientId)
            put("authorization_endpoint", doc.authorizationEndpoint)
            putJsonArray("scopes") { signinScopes.forEach(::add) }
            putJsonArray("domains") { parts.signin.domains.forEach(::add) }
            put("key_days", parts.signin.keyDays)
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    /** A code traded for a key, or refused. */
    private suspend fun token(call: ApplicationCall, parts: SigninParts) {
        val address = attempts.address(call)
        if (!attempts.admit(call, address, parts.signin.ratePerMinute, voice)) return
        when (val minted = signedIn(parts, jsonBody.parse(call))) {
            is ServerAnswer.Done ->
                call.respondText(minted.body.toString(), ContentType.Application.Json, minted.status)
            is ServerAnswer.Refused -> refused(call, address, minted.why)
            is ServerAnswer.Text -> call.respondText(minted.text, ContentType.parse(minted.contentType), minted.status)
        }
    }

    /** The key minted for the trade in [body], once the provider takes its code and its ID token checks out. */
    private suspend fun signedIn(parts: SigninParts, body: JsonObject?): ServerAnswer {
        val trade = trade(body) ?: return ServerAnswer.Refused(HttpStatusCode.Unauthorized, TRADE_FIELDS)
        if (!SigninRedirects.laptop(trade.redirect)) {
            return ServerAnswer.Refused(HttpStatusCode.Unauthorized, "redirect_uri must be a loopback callback")
        }
        val person = parts.verify(trade.code, trade.verifier, trade.redirect, trade.nonce)
        return when (person) {
            is IdVerdict.Refused -> ServerAnswer.Refused(HttpStatusCode.Unauthorized, person.why)
            is IdVerdict.Person -> keys.signedIn(person.email, trade.device, parts.signin.keyDays)
        }
    }

    /** [body]'s fields, or null when one is missing, blank or not a line of text, or the device is too long. */
    private fun trade(body: JsonObject?): Trade? {
        val read = try {
            body?.let { json.decodeFromJsonElement(Trade.serializer(), it) }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val device = read.device.trim()
        val lines = listOf(read.code, read.verifier, read.redirect, read.nonce)
        return read.copy(device = device)
            .takeIf { lines.all { it.isNotBlank() && '\n' !in it } && deviceShape.matches(device) }
    }

    private suspend fun refused(call: ApplicationCall, address: String, why: String) {
        log("[server] a sign-in from ${LogSafe.str(address)} was refused: ${LogSafe.str(why)}\n")
        voice.refuse(call, HttpStatus.UNAUTHORIZED, "sign-in refused: $why")
    }
}

// The scopes a sign-in asks for: an ID token (openid) that names the person's email (email); profile gives the
// provider's consent screen the person's own name to show.
internal val signinScopes = listOf("openid", "email", "profile")

private val CONFIG_PATH = listOf(SERVER_ROOT, "signin")
private val TOKEN_PATH = CONFIG_PATH + "token"

private const val TRADE_FIELDS = "the request must carry code, code_verifier, redirect_uri, nonce and device"

private const val NO_SIGNIN = "this server has no sign-in ([server.signin] is not set); ask its operator for a key"

// why: a device name is a label a laptop gives itself (its host name and a few letters); 64 characters hold any such
// label and keep a report's device column readable.
private const val DEVICE_MAX = 64
