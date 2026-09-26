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
        val secret = when (sent) {
            is Credentials.Bearer -> sent.token
            is Credentials.ApiKey -> sent.key
            Credentials.ClientForwarded, null -> return false
        }
        val masked = MASKED.findAll(body.orEmpty()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return masked.isNotEmpty() && masked.none { (head, tail) -> secret.startsWith(head) && secret.endsWith(tail) }
    }

    /** [failure] as the upstream's own when it is an authentication failure whose [body] names a
     *  credential [sent] cannot be: an API error, transient because the upstream refused itself and may
     *  stop, in words that say so. Anything else is [failure] unchanged. */
    public fun upstreamsOwn(failure: ClassifiedFailure, body: String?, sent: Credentials?): ClassifiedFailure {
        if (failure.type != ErrorType.AUTHENTICATION || !named(body, sent)) return failure
        return failure.copy(
            type = ErrorType.API_ERROR,
            // The star run is shortened, so the upstream's sentence fits the client's snippet whole.
            // SAFE-RENDER-EXEMPT[2026-09-26]: failure is a ClassifiedFailure, never a throwable, and its
            // message is the classifier's reading of the upstream's HTTP body, whose one secret-shaped part
            // is the upstream's own masked key, shortened further here (precedent: GrokOAuth.kt:157).
            message = "the upstream rejected a credential this account did not send: " +
                failure.message.replace(STAR_RUN, SHORT_STARS),
            transient = true,
            cause = FailureCause.UPSTREAM_REPORTED,
        )
    }

    private val MASKED = Regex("""([A-Za-z0-9_-]+)\*{4,}([A-Za-z0-9_-]+)""")
    private val STAR_RUN = Regex("""\*{4,}""")
    private const val SHORT_STARS = "****"
}
