// NEW: server mode (docs/specs/server-mode.md, F2). Whether an ID token the identity provider sent back is to be
// believed, and whose it is. Every part is checked, and any one wrong refuses it: signed with RS256 (or RS384, RS512)
// by a key the provider publishes, issued by the configured issuer, for this server's client, not yet expired, carrying
// the nonce the laptop sent, and naming a verified email in one of `[server.signin] domains`. The token came straight
// from the provider over TLS in the server's own trade, and it is checked as if it had not: a wrong token is what a
// misconfigured provider, a replayed code or a forged one looks like.
package splice.server.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.topology.PersonEmails
import splice.core.topology.ServerSignin
import splice.core.util.JsonScalars
import java.security.GeneralSecurityException
import java.security.Signature
import java.time.Clock
import java.util.Base64

/** What reading an ID token came to: the person it names, or why it is not believed. */
internal sealed class IdVerdict {
    data class Person(val email: String) : IdVerdict()

    data class Refused(val why: String) : IdVerdict()
}

internal class IdTokens(
    private val signin: ServerSignin,
    private val issuer: SigninIssuer,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val decoder = Base64.getUrlDecoder()
    private val configured = signin.issuer.trim().trimEnd('/')

    /** The person [token] names, once each check passes, for the sign-in that sent [nonce]. */
    suspend fun person(token: String, nonce: String): IdVerdict {
        val jwt = jwt(token) ?: return refused("the ID token is not a signed JWT")
        val why = unsigned(jwt) ?: tokenFault(jwt.claims, nonce) ?: emailFault(jwt.claims)
        val email = JsonScalars.str(jwt.claims, "email")?.let(PersonEmails::read)
        return if (why == null && email != null) IdVerdict.Person(email) else refused(why ?: NO_EMAIL)
    }

    /** Why [jwt] is not signed by the provider, or null when it is. */
    private suspend fun unsigned(jwt: Jwt): String? {
        val algorithm = JWS_ALGORITHMS[JsonScalars.str(jwt.header, "alg")]
            ?: return "the ID token is not signed with RS256, RS384 or RS512"
        val key = issuer.key(JsonScalars.str(jwt.header, "kid"))
            ?: return "the ID token is signed by a key the identity provider does not publish"
        val verifies = try {
            Signature.getInstance(algorithm).run {
                initVerify(key)
                update(jwt.signed.toByteArray(Charsets.US_ASCII))
                verify(bytes(jwt.signature) ?: ByteArray(0))
            }
        } catch (_: GeneralSecurityException) {
            false
        }
        return if (verifies) null else "the ID token's signature does not verify against the identity provider's keys"
    }

    /** Why [claims] are not for this sign-in: the issuer, the audience, the expiry and the nonce. */
    private fun tokenFault(claims: JsonObject, nonce: String): String? {
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull
        return when {
            !issuedHere(JsonScalars.str(claims, "iss")) -> "the ID token was issued by another issuer"
            !forThisClient(claims) -> "the ID token was issued for another client"
            exp == null -> "the ID token carries no expiry"
            clock.instant().epochSecond > exp + CLOCK_SKEW_SECONDS -> "the ID token has expired"
            JsonScalars.str(claims, "nonce") != nonce -> "the ID token's nonce is not the one this sign-in sent"
            else -> null
        }
    }

    /** Why the email [claims] name is not one this server admits: none, not verified, or in another domain. */
    private fun emailFault(claims: JsonObject): String? {
        val email = JsonScalars.str(claims, "email")?.let(PersonEmails::read)
        val verified = (claims["email_verified"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content == "true") }
        return when {
            email == null -> NO_EMAIL
            verified != true -> "$email is not a verified email at the identity provider"
            !signin.admits(email) -> "$email is not in a domain this server admits (${signin.domains.joinToString()})"
            else -> null
        }
    }

    /** Whether [iss] is the configured issuer. Google's tokens may name it without its scheme
     *  (`accounts.google.com`), which OpenID Connect's discovery for it allows, so that spelling is the same issuer. */
    private fun issuedHere(iss: String?): Boolean {
        val named = iss?.trimEnd('/') ?: return false
        return named == configured || "https://$named" == configured
    }

    /** Whether the token's audience is this server's client: `aud` names it, and when `aud` names others too, the
     *  party it was issued to (`azp`) is this client. */
    private fun forThisClient(claims: JsonObject): Boolean {
        val aud = claims["aud"]
        val audiences = (aud as? JsonArray)?.mapNotNull(JsonScalars::str) ?: listOfNotNull(JsonScalars.str(aud))
        val azp = JsonScalars.str(claims, "azp")
        return signin.clientId in audiences && (audiences.size == 1 || azp == null || azp == signin.clientId)
    }

    /** [token]'s three parts, or null when it is not a JWT whose header and claims are JSON. */
    private fun jwt(token: String): Jwt? {
        val parts = token.split('.').takeIf { it.size == JWT_PARTS } ?: return null
        val header = decoded(parts[0])
        val claims = decoded(parts[1])
        return if (header == null || claims == null) null else Jwt("${parts[0]}.${parts[1]}", header, claims, parts[2])
    }

    private fun decoded(part: String): JsonObject? =
        bytes(part)?.let { JsonScalars.objectOrNull(json, it.toString(Charsets.UTF_8)) }

    private fun bytes(part: String): ByteArray? = try {
        decoder.decode(part)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun refused(why: String): IdVerdict = IdVerdict.Refused(why)

    /** A JWT read apart: the [signed] text (header and claims), what they say, and the [signature] part, base64url. */
    private data class Jwt(val signed: String, val header: JsonObject, val claims: JsonObject, val signature: String)
}

// why: a signed JWT in compact form is three parts, header, claims and signature (RFC 7515, section 7.1).
private const val JWT_PARTS = 3
private const val NO_EMAIL = "the ID token names no email"

// The JWS algorithms an identity provider signs ID tokens with, by the JDK's name for each. `none` and the HMAC ones
// are absent: a token the server can't verify with the provider's published key is not believed.
private val JWS_ALGORITHMS = mapOf(
    "RS256" to "SHA256withRSA",
    "RS384" to "SHA384withRSA",
    "RS512" to "SHA512withRSA",
)

// why: the server's clock and the provider's may disagree by a little; thirty seconds forgives that, and is far
// short of any token's real life, which is an hour at the providers teams use.
private const val CLOCK_SKEW_SECONDS = 30L
