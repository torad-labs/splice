// NEW: V4-444 — a request sent on an API key records WHICH key sent it, so Requests can name its account and
// Usage can split a key command's spend the way it splits a subscription's.
//
// Before this, a key head's every row read `account: "primary"`: a label with no key behind it, identical across
// OpenRouter, DeepSeek and every other key command, and unchanged when the key was rotated. The key IS the account
// (Marcos, Oct 8), so the row carries the variable the key is read from and a short fingerprint of its value.
package splice.head.admission

import splice.core.auth.AuthProvider
import splice.core.auth.ClientAuthProvider
import splice.head.HeadDeps

// why: one spelling for the request list, Usage and Playground (console BUILD rows 32 and 38). One token, so it
// survives a filter query and a grep of the perf file; a surface may render the colon as it likes.
private const val KEY_ACCOUNT_SEPARATOR = ":"

/** The account a request ran on when the head holds a key rather than a login: `OPENROUTER_API_KEY:3f9a1b2c`.
 *
 *  Both halves are read from the auth's own masked description (`env_var`, `key_fingerprint`), the one channel a
 *  provider publishes them on and where /api/keys reads them too: no vendor fact enters this file and the
 *  fingerprint rule stays the provider's. A rotated key describes a new fingerprint, so it reads as a new account
 *  and the old one keeps its own history.
 *
 *  Null when there is no key to name, and then the row records what it recorded before: a head whose auth
 *  describes no variable, and a key that is missing right now (no fingerprint), are both no account rather than a
 *  made-up one. */
internal class KeyAccount(private val auth: AuthProvider, private val quotas: HeadDeps.HeadQuota) {
    /** Resolves the key in use NOW, so a `splice key set` between two requests is visible on the second.
     *
     *  Null without asking for a head whose logins answer: a pool proves the account, and a forwarded client
     *  login holds no key of splice's at all. The pool is read at CALL time, so a head that gains one stops
     *  naming a key from the next request.
     */
    suspend fun label(): String? {
        if (quotas.activePool != null || auth is ClientAuthProvider) return null
        val fields = auth.describe().fields
        val variable = fields["env_var"] ?: return null
        return fields["key_fingerprint"]?.let { print -> variable + KEY_ACCOUNT_SEPARATOR + print }
    }
}
