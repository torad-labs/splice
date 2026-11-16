// An OpenID Connect provider for the sign-in tests (docs/specs/server-mode.md, F2), on loopback and shaped like
// Google's: a discovery document, a JWKS, and a token endpoint that checks the client secret and the PKCE verifier and
// answers an RS256 ID token. [code] is the browser's part: the person signs in and the provider issues a code for the
// laptop's redirect. Each field of [Identity] makes the ID token wrong in one way a server must refuse.
package splice.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Who signs in at the provider, and how the ID token it then signs is wrong, if it is. */
internal data class Identity(
    val email: String,
    val verified: Boolean = true,
    val aud: String? = null,
    val iss: String? = null,
    /** Seconds from now to `exp`; negative for a token that has expired. */
    val expiresIn: Long = 3_600,
    /** Signed with a key the JWKS doesn't publish. */
    val foreignKey: Boolean = false,
)

internal class StandInIssuer : AutoCloseable {
    val clientId = "splice-team"
    val clientSecret = "stand-in-client-secret"

    /** The form of every token request the provider was sent. */
    val tokenRequests: MutableList<Map<String, String>> = CopyOnWriteArrayList()

    private val own = rsaPair()
    private val foreign = rsaPair()
    private val codes = ConcurrentHashMap<String, Issued>()
    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { answer(it) } }
        start()
    }

    val url: String = "http://127.0.0.1:${server.address.port}"

    /** The browser's part of a sign-in: [who] signs in, and the code the provider sends to [redirect]. */
    fun code(who: Identity, challenge: String, nonce: String, redirect: String): String {
        val code = UUID.randomUUID().toString()
        codes[code] = Issued(who, challenge, nonce, redirect)
        return code
    }

    override fun close() {
        server.stop(0)
    }

    private fun answer(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == "/.well-known/openid-configuration" -> send(exchange, HTTP_OK, discovery())
            path == "/jwks" -> send(exchange, HTTP_OK, jwks())
            path == "/token" && exchange.requestMethod == "POST" -> token(exchange)
            else -> send(exchange, HTTP_NOT_FOUND, """{"error":"not_found"}""")
        }
    }

    private fun discovery(): String = buildJsonObject {
        put("issuer", url)
        put("authorization_endpoint", "$url/authorize")
        put("token_endpoint", "$url/token")
        put("jwks_uri", "$url/jwks")
    }.toString()

    private fun jwks(): String = buildJsonObject {
        val key = own.public as RSAPublicKey
        putJsonArray("keys") {
            addJsonObject {
                put("kty", "RSA")
                put("kid", KID)
                put("use", "sig")
                put("alg", "RS256")
                put("n", unsigned(key.modulus))
                put("e", unsigned(key.publicExponent))
            }
        }
    }.toString()

    private fun token(exchange: HttpExchange) {
        val form = form(exchange.requestBody.use { it.readBytes() }.decodeToString())
        tokenRequests += form
        val issued = codes.remove(form["code"])
        val fault = issued?.let { fault(form, it) } ?: "unknown code"
        if (issued == null || fault.isNotEmpty()) {
            return send(exchange, HTTP_BAD_REQUEST, """{"error":"invalid_grant","error_description":"$fault"}""")
        }
        send(exchange, HTTP_OK, buildJsonObject { put("id_token", idToken(issued)) }.toString())
    }

    /** A form body's fields, decoded. */
    private fun form(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate { pair ->
        val (name, value) = pair.split('=', limit = 2).map { URLDecoder.decode(it, Charsets.UTF_8) }
        name to value
    }

    /** Why the token request [form] does not redeem the code [issued]; empty when it does. */
    private fun fault(form: Map<String, String>, issued: Issued): String = when {
        challenge(form["code_verifier"]) != issued.challenge -> "code_verifier does not match"
        form["grant_type"] != "authorization_code" -> "grant_type"
        form["client_id"] != clientId || form["client_secret"] != clientSecret -> "invalid_client"
        form["redirect_uri"] != issued.redirect -> "redirect_uri differs"
        else -> ""
    }

    private fun idToken(issued: Issued): String {
        val who = issued.who
        val now = System.currentTimeMillis() / MILLIS_PER_SECOND
        val claims = buildJsonObject {
            put("iss", who.iss ?: url)
            put("aud", who.aud ?: clientId)
            put("sub", "sub-${who.email}")
            put("email", who.email)
            put("email_verified", who.verified)
            put("nonce", issued.nonce)
            put("iat", now)
            put("exp", now + who.expiresIn)
        }
        val head = base64.encodeToString("""{"alg":"RS256","kid":"$KID","typ":"JWT"}""".toByteArray())
        val signed = "$head.${base64.encodeToString(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign((if (who.foreignKey) foreign else own).private)
            update(signed.toByteArray())
            sign()
        }
        return "$signed.${base64.encodeToString(signature)}"
    }

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** [n] as JWK writes it: its unsigned big-endian bytes, base64url. */
    private fun unsigned(n: BigInteger): String =
        base64.encodeToString(n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())

    /** The S256 challenge of [verifier], as a provider checks the one an authorization was asked with. */
    private fun challenge(verifier: String?): String? =
        verifier?.let { base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }

    private fun rsaPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair()

    private data class Issued(val who: Identity, val challenge: String, val nonce: String, val redirect: String)
}

private const val KID = "stand-in-1"
private const val RSA_BITS = 2048
private const val MILLIS_PER_SECOND = 1_000L
private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
