// NEW: v0.4.0 — the ONE derivation of a scoped capability from the management key, shared by the MCP
// access key (FEATURES.md §8) and the turn key a launched session holds. HMAC-SHA256 under a per-use
// scope: one-way, so a holder learns nothing of the management key, and domain-separated, so no
// scope's key opens another's door. Two copies of this body drifted-by-construction the day they
// were written; one derivation keeps every scoped key the same shape.
package splice.core.auth

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val ALGORITHM = "HmacSHA256"

public object ScopedKey {

    /** HMAC-SHA256 of [scope] keyed by [secret], both UTF-8, as lowercase hex. */
    public fun derive(secret: String, scope: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(UTF_8), ALGORITHM))
        return mac.doFinal(scope.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
