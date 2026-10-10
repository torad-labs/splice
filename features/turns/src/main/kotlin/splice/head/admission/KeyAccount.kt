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

/** What a row records as its account when no login was proved for the turn. Three states rather than a nullable
 *  string, because "this head holds no key" and "this head's key is gone" are different facts and a row says
 *  different things about them. */
internal sealed class KeyNaming {
    /** Not a key head: its login is one splice cannot name, so the head's own fallback label stands. */
    data object None : KeyNaming()

    /** The key that sent the request, as `OPENROUTER_API_KEY:3f9a1b2c`. */
    data class Named(val label: String) : KeyNaming()

    /** A key head whose key is not there right now: no account at all, rather than a label with nothing behind
     *  it. Those turns end auth-missing, and a made-up account on them is what this change set out to remove. */
    data object Missing : KeyNaming()
}

/** Names the key a head reads, for the account its rows record.
 *
 *  Both halves of a name come from the auth's own masked description (`env_var`, `key_fingerprint`), the one
 *  channel a provider publishes them on and where /api/keys reads them too: no vendor fact enters this file and
 *  the fingerprint rule stays the provider's. A rotated key describes a new fingerprint, so it reads as a new
 *  account and the one it replaced keeps its own history. */
internal class KeyAccount(private val auth: AuthProvider, private val quotas: HeadDeps.HeadQuota) {
    /** Resolves the key in use NOW, so a `splice key set` between two requests is visible on the second.
     *
     *  [KeyNaming.None] without asking, for a head whose logins answer: a pool proves the account, and a
     *  forwarded client login holds no key of splice's at all. The pool is read at CALL time, so a head that
     *  gains one stops naming a key from the next request. */
    suspend fun naming(): KeyNaming {
        if (quotas.activePool != null || auth is ClientAuthProvider) return KeyNaming.None
        val fields = auth.describe().fields
        val variable = fields["env_var"] ?: return KeyNaming.None
        return fields["key_fingerprint"]
            ?.let { print -> KeyNaming.Named(variable + KEY_ACCOUNT_SEPARATOR + print) }
            ?: KeyNaming.Missing
    }

    /** The name alone, for a surface that records a name or nothing at all: null for both [KeyNaming.None] and
     *  [KeyNaming.Missing], which is right where the alternative was already no account. */
    suspend fun label(): String? = (naming() as? KeyNaming.Named)?.label
}
