// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"). A client-auth head forwards the caller's own credential in the
// turn's headers, and the upstream merge puts those headers AFTER the account's own auth header, so a login the pool
// selected would always be overridden by the caller's token. This is the one rule for which credential rides: the
// selected login's, unless it is the caller's own (or the head holds none), in which case the caller's headers go
// untouched, byte for byte. Read per attempt with that attempt's credentials, so a mid-request switch follows it.
package splice.head.transport

import splice.core.auth.CredentialKey
import splice.core.auth.Credentials

internal object CallerCredential {
    /** [turnHeaders] as they should ride beside [selected]: unchanged when [selected] is the caller's own credential
     *  or forwards it, else without the caller's credential carriers, so [selected]'s own auth header is the one sent. */
    fun over(turnHeaders: Map<String, String>, selected: Credentials): Map<String, String> {
        // A head that forwards holds no credential of its own, so the caller's IS the one that rides.
        val own = CredentialKey.headers(selected, emptyMap()).takeIf { it.isNotEmpty() } ?: return turnHeaders
        val caller = CredentialKey.fromHeaders(turnHeaders)
        val mine = caller == null || caller == CredentialKey.fromHeaders(own)
        val carriers = own.keys.map(String::lowercase) + STANDARD_CARRIERS
        return if (mine) turnHeaders else turnHeaders.filterKeys { it.lowercase() !in carriers }
    }
}

/** The carriers a caller can put its credential in: the two CredentialKey reads. A selected login that writes a
 *  custom header still displaces both, so the caller's credential can never ride beside it. */
private val STANDARD_CARRIERS: List<String> = listOf("authorization", "x-api-key")
