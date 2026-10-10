// NEW: V4-242 (2026-09-26) — a 401 that names a credential splice did not send is the upstream's failure.
//
// During the Codex outage of 2026-09-25 the HTTP side answered 401 invalid_api_key naming a masked
// OpenAI service key (`sk-svcac…fvMA`), while the account had sent its own sign-in token. Read as
// every 401 is, it told the user their sign-in had expired and took the account out of the pool. An
// upstream masks the key it rejects the same way everywhere: a visible head, a run of stars, a visible
// tail. When no masked key in the body can be the credential that was sent, the credential was not what
// the upstream refused. The comparison runs here, in-process, and the credential goes nowhere else.
package splice.upstream.failure

import splice.core.auth.Credentials
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause

/** Whether an upstream's authentication failure names a credential other than the one sent. */
public object ForeignCredential {
    /** True when [body] names at least one masked credential and [sent] can be none of them. A body
     *  that names none, and a credential splice does not hold ([Credentials.ClientForwarded]), say
     *  nothing either way: false, so the failure is read as it always was. */
    public fun named(body: String?, sent: Credentials?): Boolean {
        val secret = secretOf(sent) ?: return false
        val masked = MASKED.findAll(body.orEmpty()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return masked.isNotEmpty() && masked.none { (head, tail) -> secret.startsWith(head) && secret.endsWith(tail) }
    }

    /** V4-444: [text] with the credential that was SENT replaced by a mask, for a surface that shows the
     *  upstream's own words (the console's Requests list). A provider that echoes a request header back into its
     *  error body would otherwise carry our key onto that surface. EXACT, never a pattern: only the one secret in
     *  hand is removed, so the provider's sentence survives whole and no key-shaped text of its own is eaten. */
    public fun withoutSentSecret(text: String, sent: Credentials?): String {
        val secret = secretOf(sent)?.takeIf { it.isNotBlank() } ?: return text
        return text.replace(secret, SHORT_STARS)
    }

    /** The secret splice sent, for comparing a body against it. Null when there is none to compare: a forwarded
     *  client login is one splice never reads. */
    private fun secretOf(sent: Credentials?): String? = when (sent) {
        is Credentials.Bearer -> sent.token
        is Credentials.ApiKey -> sent.key
        Credentials.ClientForwarded, null -> null
    }

    /** [read] as the upstream's own when it is an authentication failure whose [body] names a
     *  credential [sent] cannot be: an API error, transient because the upstream refused itself and may
     *  stop, in words that say so. Anything else is [read] unchanged. */
    public fun upstreamsOwn(read: ClassifiedFailure, body: String?, sent: Credentials?): ClassifiedFailure {
        if (read.type != ErrorType.AUTHENTICATION || !named(body, sent)) return read
        return read.copy(
            type = ErrorType.API_ERROR,
            // The star run is shortened, so the upstream's sentence fits the client's snippet whole.
            message = "the upstream rejected a credential this account did not send: " +
                read.message.replace(STAR_RUN, SHORT_STARS),
            transient = true,
            cause = FailureCause.UPSTREAM_REPORTED,
        )
    }

    private val MASKED = Regex("""([A-Za-z0-9_-]+)\*{4,}([A-Za-z0-9_-]+)""")
    private val STAR_RUN = Regex("""\*{4,}""")
    private const val SHORT_STARS = "****"
}
