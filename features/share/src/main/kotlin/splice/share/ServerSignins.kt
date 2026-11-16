// NEW: server mode on a laptop (docs/specs/server-mode.md, F2). The laptop's two asks of a server when its person signs
// in: the sign-in config (`GET /server/signin`: the identity provider, the client id, where to send the person, the
// scopes), which is all a laptop needs, so nothing is configured by hand; and the trade (`POST /server/signin/token`) of
// the code the provider sent back, with its PKCE verifier, for the person's key. The server's refusal is said in its
// own words.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.net.URI

/** What `GET /server/signin` answers: everything a laptop needs to send its person to the identity provider. */
@Serializable
public data class ServerSigninConfig(
    val issuer: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    val scopes: List<String>,
    val domains: List<String> = emptyList(),
)

/** What the laptop sends with the code: the PKCE [verifier], the [redirect] the code came back to, the [nonce] the
 *  authorization asked for, and the [device] this laptop signs in as. */
public data class SigninTrade(
    val code: String,
    val verifier: String,
    val redirect: String,
    val nonce: String,
    val device: String,
) {
    override fun toString(): String = "SigninTrade(redirect=$redirect, device=$device, code=<redacted>)"
}

public class ServerSignins(io: CoroutineDispatcher) {
    private val http = ShareHttp(io)
    private val json = Json { ignoreUnknownKeys = true }

    /** [server]'s sign-in config. */
    public suspend fun config(server: String): Asked<ServerSigninConfig> =
        http.read(server, http.get(URI.create("$server/server/signin")), not = "not with a splice server's sign-in") {
            http.decoded(it, ServerSigninConfig.serializer())
        }

    /** The key [server] mints for [trade], or why it would not. */
    public suspend fun trade(server: String, trade: SigninTrade): Asked<ServerSignedIn> {
        val body = buildJsonObject {
            put("code", trade.code)
            put("code_verifier", trade.verifier)
            put("redirect_uri", trade.redirect)
            put("nonce", trade.nonce)
            put("device", trade.device)
        }
        val answer = http.post(URI.create("$server/server/signin/token"), body.toString())
        return http.read(server, answer, not = "not with a key") { signedIn(JsonScalars.objectOrNull(json, it)) }
    }

    /** The sign-in a server's [minted] answer names, or null when it lacks the person, the key or its prefix. */
    private fun signedIn(minted: JsonObject?): ServerSignedIn? {
        val fields = MINTED_FIELDS.map { JsonScalars.str(minted, it) }
        if (null in fields) return null
        val (person, key, prefix) = fields.requireNoNulls()
        return ServerSignedIn(person, key, prefix, JsonScalars.str(minted, "expires_at"))
    }
}

// What a minted key's answer must name, in the order ServerSignedIn takes them.
private val MINTED_FIELDS = listOf("person", "key", "prefix")
