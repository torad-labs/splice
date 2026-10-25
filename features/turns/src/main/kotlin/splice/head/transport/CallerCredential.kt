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

// LAN share (docs/specs/lan-share.md). A turn's headers are the held login's own, then the caller's, and the
// caller's replace the login's name for name. That is right for a single value and wrong for a list: a login that
// needs a beta flag the caller did not name (a held Claude login's OAuth beta, on a node's turn) lost it to the
// caller's list. A list-valued header carries both lists, the caller's flags first, each flag once.
internal object ListValuedHeaders {
    /** The headers whose value is a COMMA-SEPARATED LIST, so repeated field lines are one value split across lines,
     *  and two parties' values are joined rather than one replacing the other.
     *
     *  Only `anthropic-beta` qualifies. `Authorization` and `x-api-key` are singleton credentials and
     *  `anthropic-version` is a single token: joining a repeat of any of those would forge a value nobody sent, so
     *  for them the first line wins, which is what a singleton field means. */
    val names: Set<String> = setOf("anthropic-beta")

    // why: RFC 9110 5.3 joins repeated list-valued field lines with a comma and OWS; the vendor parses the beta flags
    // off exactly this separator, so it is the wire form, not a formatting choice.
    const val FIELD_LINE_SEPARATOR: String = ", "

    /** [caller] over [account], as a map's plus puts it, except that a list-valued header both carry holds every flag
     *  of both: the caller's in its own order, then those of the account's it did not name. */
    fun over(account: Map<String, String>, caller: Map<String, String>): Map<String, String> {
        val joined = caller.mapValues { (name, value) ->
            if (name.lowercase() in names) {
                val accounts = account.filterKeys { it.equals(name, ignoreCase = true) }.values.flatMap(::flags)
                (flags(value) + accounts).distinct().joinToString(FIELD_LINE_SEPARATOR)
            } else {
                value
            }
        }
        val listed = joined.keys.map(String::lowercase).filter { it in names }.toSet()
        return account.filterKeys { it.lowercase() !in listed } + joined
    }

    private fun flags(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)
}

/** The carriers a caller can put its credential in: the two CredentialKey reads. A selected login that writes a
 *  custom header still displaces both, so the caller's credential can never ride beside it. */
private val STANDARD_CARRIERS: List<String> = listOf("authorization", "x-api-key")
