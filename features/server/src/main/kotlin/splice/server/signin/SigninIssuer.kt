// NEW: server mode (docs/specs/server-mode.md, F2). The server's side of a team member's sign-in with the company's
// identity provider: the provider's OpenID Connect discovery document (where a laptop sends the person, where a code
// is traded, where the signing keys are published), its published keys, and the trade of an authorization code for an
// ID token. Only the server holds the client secret, so the laptop brings the code here and never sees it. What the
// provider answers is read here; whether the ID token is to be believed is IdTokens'.
package splice.server.signin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.topology.ServerSignin
import splice.core.util.Cancellables
import splice.core.util.FormEncoding
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.share.Asked
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64

/** Where the provider's discovery document says each part of a sign-in happens. */
internal data class IssuerDoc(val authorizationEndpoint: String, val tokenEndpoint: String, val jwksUri: String)

/** What the server holds to ask the provider with: the client secret, read from the environment at boot. */
internal class SigninClient(val signin: ServerSignin, private val secret: String?) {
    /** The token request's client fields, form-encoded: the id always, and the secret when the provider registered
     *  one. */
    fun fields(): String = FormEncoding.formEncode("client_id" to signin.clientId) +
        secret?.let { "&" + FormEncoding.formEncode("client_secret" to it) }.orEmpty()

    override fun toString(): String = "SigninClient(issuer=${signin.issuer}, secret=<redacted>)"
}

internal class SigninIssuer(
    private val client: SigninClient,
    private val io: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val issuer = client.signin.issuer.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(ISSUER_CONNECT_MS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val rsa = KeyFactory.getInstance("RSA")

    @Volatile
    private var doc: IssuerDoc? = null

    @Volatile
    private var keys: Map<String, PublicKey> = emptyMap()

    @Volatile
    private var keysReadAt = 0L

    /** The provider's discovery document, read once and kept; asked again only after a read that failed. */
    suspend fun doc(): Asked<IssuerDoc> {
        doc?.let { return Asked.Had(it) }
        return get("$issuer$DISCOVERY_PATH").then { body ->
            val read = JsonScalars.objectOrNull(json, body)
            val found = IssuerDoc(
                JsonScalars.str(read, "authorization_endpoint").orEmpty(),
                JsonScalars.str(read, "token_endpoint").orEmpty(),
                JsonScalars.str(read, "jwks_uri").orEmpty(),
            )
            if (listOf(found.authorizationEndpoint, found.tokenEndpoint, found.jwksUri).any(String::isBlank)) {
                Asked.Refused("$issuer$DISCOVERY_PATH is not an OpenID Connect discovery document")
            } else {
                doc = found
                Asked.Had(found)
            }
        }
    }

    /** Trades [code] for an ID token at the provider's token endpoint, proving the laptop's PKCE [verifier] and
     *  naming the [redirect] the code was sent to. The provider's error body is never repeated: it may echo what it
     *  was sent. */
    suspend fun trade(code: String, verifier: String, redirect: String): Asked<String> {
        val form = FormEncoding.formEncode(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirect,
            "code_verifier" to verifier,
        ) + "&" + client.fields()
        return doc().then { posted(it.tokenEndpoint, form) }.then(::idToken)
    }

    /** The provider's signing key [kid] names. A key not yet seen reads the published keys again, at most once a
     *  minute, so a provider that rotated its keys is followed and a caller naming made-up keys costs it nothing. */
    suspend fun key(kid: String?): PublicKey? {
        val known = keyNamed(kid)
        val fresh = clock.millis() - keysReadAt < JWKS_REREAD_MS && keys.isNotEmpty()
        if (known != null || fresh) return known
        val read = doc().then { get(it.jwksUri) }
        if (read is Asked.Had) {
            keysReadAt = clock.millis()
            keys = published(read.value)
        }
        return keyNamed(kid)
    }

    private suspend fun posted(endpoint: String, form: String): Asked<HttpResponse<String>> {
        val request = request(endpoint) ?: return unaskable(endpoint)
        return send(
            request.header("content-type", "application/x-www-form-urlencoded")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
        )
    }

    /** The ID token in the provider's [answered] trade, or why there is none, naming at most its OAuth error code. */
    private fun idToken(answered: HttpResponse<String>): Asked<String> {
        val body = JsonScalars.objectOrNull(json, answered.body())
        val token = JsonScalars.str(body, "id_token")
        val named = JsonScalars.str(body, "error")?.takeIf { it.matches(OAUTH_ERROR_CODE) }?.let { " ($it)" }
        return when {
            answered.statusCode() != HttpStatus.OK -> Asked.Refused(
                "the identity provider refused the code (HTTP ${answered.statusCode()}${named.orEmpty()})",
            )
            token == null -> Asked.Refused("the identity provider sent no ID token")
            else -> Asked.Had(token)
        }
    }

    /** The key [kid] names, or the one key there is when the token names none. */
    private fun keyNamed(kid: String?): PublicKey? = if (kid == null) keys.values.singleOrNull() else keys[kid]

    /** The RSA signing keys of a JWKS document, by key id. */
    private fun published(body: String): Map<String, PublicKey> {
        val listed = JsonScalars.objectOrNull(json, body)?.get("keys") as? JsonArray ?: return emptyMap()
        return listed.mapNotNull { it as? JsonObject }
            .filter { JsonScalars.str(it, "kty") == "RSA" && JsonScalars.str(it, "use") in setOf(null, "sig") }
            .mapNotNull { jwk -> rsaKey(jwk)?.let { (JsonScalars.str(jwk, "kid") ?: "") to it } }
            .toMap()
    }

    /** [jwk]'s RSA public key, or null for one whose numbers are not base64url or not a key. */
    private fun rsaKey(jwk: JsonObject): PublicKey? {
        val n = JsonScalars.str(jwk, "n") ?: return null
        val e = JsonScalars.str(jwk, "e") ?: return null
        val decoder = Base64.getUrlDecoder()
        return try {
            rsa.generatePublic(RSAPublicKeySpec(BigInteger(1, decoder.decode(n)), BigInteger(1, decoder.decode(e))))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: InvalidKeySpecException) {
            null
        }
    }

    private suspend fun get(uri: String): Asked<String> {
        val request = request(uri) ?: return unaskable(uri)
        return when (val sent = send(request.GET().build())) {
            is Asked.Refused -> sent
            is Asked.Had -> if (sent.value.statusCode() == HttpStatus.OK) {
                Asked.Had(sent.value.body())
            } else {
                Asked.Refused("$uri answered HTTP ${sent.value.statusCode()}")
            }
        }
    }

    /** A request to [uri] with the answer timeout, or null when [uri] is no address. */
    private fun request(uri: String): HttpRequest.Builder? = try {
        HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofMillis(ISSUER_ANSWER_MS))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun unaskable(uri: String): Asked.Refused =
        Asked.Refused("the identity provider names $uri, which is not an address the server can ask")

    private suspend fun send(request: HttpRequest): Asked<HttpResponse<String>> {
        val sent = withContext(io) {
            Cancellables.runCatchingCancellable { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        }
        return sent.fold(
            onSuccess = { Asked.Had(it) },
            onFailure = {
                val where = "${request.uri().scheme}://${request.uri().authority}"
                Asked.Refused("the identity provider at $where can't be reached: ${SafeFailureText.render(it)}")
            },
        )
    }
}

private const val DISCOVERY_PATH = "/.well-known/openid-configuration"

// An OAuth error code is a short word of letters and underscores (RFC 6749 5.2); anything else is not repeated.
private val OAUTH_ERROR_CODE = Regex("^[a-z_]{1,40}$")

// why: the provider is a large service on the internet; five seconds to connect says one that is down while the
// person still waits at the laptop.
private const val ISSUER_CONNECT_MS = 5_000L

// why: a token trade answers in well under a second; fifteen covers a slow provider and still ends a hung one before
// the laptop gives up on the server.
private const val ISSUER_ANSWER_MS = 15_000L

// why: providers rotate signing keys over days, publishing the next one ahead; reading the keys again at most once a
// minute follows a rotation at once and caps what a token naming made-up keys can make the server ask.
private const val JWKS_REREAD_MS = 60_000L
