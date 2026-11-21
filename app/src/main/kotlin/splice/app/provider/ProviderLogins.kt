// NEW: gateway-no-heads. A provider's login as the gateway reads it: one rule for whether a turn can run on it now, and
// the words that say why not, which `splice claude --json` and `splice desktop` print to the person. They were
// DesktopLineup's, read off a running head; they are the provider's, read off its credential, so a provider with no head
// says them too.
package splice.app.provider

import splice.app.auth.API_KEY
import splice.app.auth.claude.AddedClaudeLogins
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.topology.AuthKindRegistry
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.head.HeldLogins

/** Whether a turn can run on a provider now. */
internal sealed class ProviderLogin {
    data object Ready : ProviderLogin()

    /** [reason] says what is missing and how to give it, in the words the person reads. */
    data class Missing(val reason: String) : ProviderLogin()
}

internal class ProviderLogins(private val added: HeldLogins = AddedClaudeLogins()) {
    /** [auth]'s login, for a turn through the gateway. A provider that forwards the caller's own Claude login
     *  ([forwardsCaller]) runs the turn on the accounts added to splice, named by [labels] in its pool, and never on the
     *  person's own login, so with none added it is [ProviderLogin.Missing]. Any other provider runs on the credential
     *  it holds, and is missing it when [auth] says so; [signIn] is the command that signs it in. */
    suspend fun of(
        auth: AuthProvider,
        forwardsCaller: Boolean,
        labels: List<String>,
        signIn: String,
    ): ProviderLogin {
        if (forwardsCaller) return missing(NONE_ADDED.takeIf { labels.none(added::held) })
        val described = Cancellables.runCatchingCancellable { auth.describe() }.getOrElse { failure ->
            return ProviderLogin.Missing(
                "its login could not be read (${SafeFailureText.render(failure)}); sign in with `$signIn`",
            )
        }
        return missing(absence(described, signIn))
    }

    /** Whether the provider is called with a login splice holds (a ChatGPT, Grok, Kimi or Muse sign-in, or accounts
     *  added to a Claude one), as against an API key or a local server. */
    fun usesLogin(forwardsCaller: Boolean, authKind: String): Boolean =
        forwardsCaller || AuthKindRegistry.isOAuth(authKind)

    private fun missing(why: String?): ProviderLogin = why?.let(ProviderLogin::Missing) ?: ProviderLogin.Ready

    /** Why a provider has no login, by [auth] as it describes itself, or null when it has one. */
    private fun absence(auth: AuthDescription, signIn: String): String? = when {
        signedIn(auth) -> null
        auth.kind == API_KEY -> keyless(auth)
        else -> "it is signed out; sign in with `$signIn`"
    }

    /** Why a provider on an API key with none has no login, and how to give it one: its key file, or the key store. */
    private fun keyless(auth: AuthDescription): String {
        auth.fields[KEY_FILE_FIELD]?.let { return "its key file $it holds no key" }
        val name = auth.fields[KEY_ENV_FIELD] ?: return "it has no API key"
        return "it has no API key; set one with `printf '%s' \"\$KEY\" | splice key set $name --stdin`"
    }

    /** A login that is there and has not expired: an access token that no refresh can renew any more is gone. */
    private fun signedIn(auth: AuthDescription): Boolean = auth.present && EXPIRED_FIELD !in auth.fields
}

// Why a provider that forwards the caller's own Claude login has none while no account is added to it.
private const val NONE_ADDED = "no Claude account is added to it, and the door never sends your own Claude login; " +
    "add one on splice's accounts page (`splice console`)"

// The field a login's description carries once its refresh has been refused for good (an invalid grant).
private const val EXPIRED_FIELD = "refresh_latched"

// The fields an API-key login's description names its key's place by: the key file it reads, or the variable it is
// stored under.
private const val KEY_FILE_FIELD = "key_file"
private const val KEY_ENV_FIELD = "env_var"
