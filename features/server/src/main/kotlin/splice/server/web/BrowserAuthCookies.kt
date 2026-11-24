package splice.server.web

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.auth.Pkce
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The state bound to the browser that began the OIDC exchange, never kept in a request URL. */
@Serializable
internal data class BrowserFlow(val state: String, val verifier: String, val nonce: String, val returnTo: String)

/** Separate HMAC purposes make a flow cookie and a session cookie non-interchangeable. */
internal class BrowserAuthCookies(private val clock: Clock, random: SecureRandom = SecureRandom()) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val signingKey = SecretKeySpec(ByteArray(RANDOM_BYTES).also(random::nextBytes), HMAC)
    private val randomness = random

    fun flow(returnTo: String): BrowserFlow = BrowserFlow(secret(), secret(), secret(), returnTo)

    fun pkce(flow: BrowserFlow): Pkce {
        val digest = MessageDigest.getInstance("SHA-256").digest(flow.verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(flow.verifier, encoder.encodeToString(digest))
    }

    fun start(call: ApplicationCall, flow: BrowserFlow) {
        val encoded = encoder.encodeToString(Json.encodeToString(BrowserFlow.serializer(), flow).toByteArray())
        set(call, FLOW_COOKIE, sign(FLOW_COOKIE, encoded, FLOW_SECONDS), FLOW_SECONDS)
    }

    fun pending(call: ApplicationCall): BrowserFlow? {
        val encoded = verified(call, FLOW_COOKIE) ?: return null
        return try {
            val text = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
            Json.decodeFromString(BrowserFlow.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun finish(call: ApplicationCall) {
        set(call, FLOW_COOKIE, "", 0)
    }

    fun session(call: ApplicationCall, reference: String, seconds: Int) {
        set(call, SESSION_COOKIE, sign(SESSION_COOKIE, reference, seconds), seconds)
    }

    fun reference(call: ApplicationCall): String? = verified(call, SESSION_COOKIE)

    private fun secret(): String = encoder.encodeToString(ByteArray(RANDOM_BYTES).also(randomness::nextBytes))

    private fun sign(name: String, payload: String, seconds: Int): String {
        val signed = "${clock.instant().epochSecond + seconds}.$payload"
        return "$COOKIE_MARK$signed.${signature(name, signed)}"
    }

    private fun verified(call: ApplicationCall, name: String): String? {
        val packed = call.request.cookies[name]?.takeIf { it.startsWith(COOKIE_MARK) }?.removePrefix(COOKIE_MARK)
            ?: return null
        val signed = packed.substringBeforeLast('.')
        val expiry = signed.substringBefore('.').toLongOrNull()
        if (expiry == null || clock.instant().epochSecond >= expiry) return null
        val supplied = packed.substringAfterLast('.').toByteArray(Charsets.US_ASCII)
        val expected = signature(name, signed).toByteArray(Charsets.US_ASCII)
        return signed.substringAfter('.').takeIf { MessageDigest.isEqual(expected, supplied) }
    }

    private fun signature(name: String, text: String): String = Mac.getInstance(HMAC).run {
        init(signingKey)
        encoder.encodeToString(doFinal("$name:$text".toByteArray(Charsets.UTF_8)))
    }

    private fun set(call: ApplicationCall, name: String, value: String, seconds: Int) {
        call.response.headers.append(
            HttpHeaders.SetCookie,
            "$name=$value; Path=/; Max-Age=$seconds; Secure; HttpOnly; SameSite=Lax",
        )
    }
}

internal object BrowserCookieWire {
    private val sealed = Regex("""sfa\.[0-9]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}""")

    /** Session and flow proofs never leave the server in provider-bound headers or query parameters. */
    fun heldIn(text: String): Boolean = sealed.containsMatchIn(text)
}

private const val COOKIE_MARK = "sfa."
private const val HMAC = "HmacSHA256"
private const val RANDOM_BYTES = 32
private const val FLOW_SECONDS = 300
private const val FLOW_COOKIE = "__Host-splice-forward-flow"
private const val SESSION_COOKIE = "__Host-splice-forward"
