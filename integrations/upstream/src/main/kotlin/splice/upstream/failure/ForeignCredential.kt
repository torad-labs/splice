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
import splice.core.util.ERR_SNIPPET

/** Whether an upstream's authentication failure names a credential other than the one sent. */
public object ForeignCredential {
    /** Whether a masked key a reply names could be the credential [sent], for the foreign-key rule every API type
     *  shares (Replies). The comparison runs in this process and the secret goes nowhere else: a credential splice
     *  does not hold ([Credentials.ClientForwarded], or none) could be any key, so it answers true to every mask, which
     *  says nothing either way and leaves the reply read as its status. */
    public fun sentKey(sent: Credentials?): SentKey {
        val secret = secretOf(sent)
        return SentKey { head, tail -> secret == null || (secret.startsWith(head) && secret.endsWith(tail)) }
    }

    /** V4-444: the words the UPSTREAM itself said, for a surface that labels them as its own (the console's Requests
     *  list), bounded and with the credential [sent] masked out. [said] is what the provider said as its reader kept
     *  it (ProviderWordsFilter, which reads the provider's own message field), so nothing splice wrote reaches this
     *  surface and no reading of the body happens twice (failure-handling slice 3). */
    public fun upstreamsWords(said: String?, sent: Credentials?): String? =
        said?.trim()?.takeIf { it.isNotBlank() }?.let { withoutSentSecret(it, sent).take(ERR_SNIPPET) }

    /** V4-444: [text] with the credential that was SENT replaced by a mask, for a surface that shows the
     *  upstream's own words (the console's Requests list). A provider that echoes a request header back into its
     *  error body would otherwise carry our key onto that surface. EXACT, never a pattern: only the one secret in
     *  hand is removed, so the provider's sentence survives whole and no key-shaped text of its own is eaten. */
    private fun withoutSentSecret(text: String, sent: Credentials?): String {
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

    private const val SHORT_STARS = "****"
}
