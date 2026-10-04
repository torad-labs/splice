// NEW: one internal join key for forwarded login holds and account standings.
package splice.core.auth

import java.security.MessageDigest
import java.util.HexFormat

/** A one-way key of the effective credential carriers, never a login label or a value to log. */
public object CredentialKey {
    /** Exactly the credential carrier emitted upstream; caller headers override it before name folding. */
    public fun headers(credentials: Credentials, extra: Map<String, String>): Map<String, String> {
        val own = when (credentials) {
            is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${credentials.token}")
            is Credentials.ApiKey -> mapOf(credentials.header to "${credentials.prefix}${credentials.key}")
            Credentials.ClientForwarded -> emptyMap()
        }
        return own + extra
    }

    /** Header names are case-insensitive; last spelling wins just as in the upstream wire merge. */
    public fun fromHeaders(headers: Map<String, String>, declaredCarrier: String? = null): String? {
        val normalized = headers.entries.associate { it.key.lowercase() to it.value }
        val names = (CREDENTIAL_CARRIERS + listOfNotNull(declaredCarrier?.lowercase())).distinct()
        val carriers = names.mapNotNull { name ->
            normalized[name]?.takeIf(String::isNotBlank)?.let { name to it }
        }
        if (carriers.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        carriers.forEach { (name, value) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

private val CREDENTIAL_CARRIERS = listOf("authorization", "x-api-key")
