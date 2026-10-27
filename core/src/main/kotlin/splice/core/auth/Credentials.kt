// NEW: credential shapes for the provider SPI (plan). Secrets never leave these types
// unmasked — introspection surfaces (/mgmt/auth, /api/auth) consume AuthDescription only.
package splice.core.auth

import splice.core.failure.LoginOwner

public sealed class Credentials {
    public data class Bearer(
        val token: String,
        val accountId: String? = null,
    ) : Credentials() {
        /** This file's first line is the invariant: secrets never leave these types unmasked. The
         *  GENERATED toString breaks it — one `"...: $creds"` in a log line, an exception or a test
         *  assertion puts the bearer token in a file. accountId is not a secret and stays readable. */
        override fun toString(): String = "Bearer(token=<redacted>, accountId=$accountId)"
    }

    public data class ApiKey(
        val key: String,
        val header: String = "Authorization",
        val prefix: String = "Bearer ",
    ) : Credentials() {
        /** As [Bearer]: the key is the secret, the header and prefix are wire shape and stay readable. */
        override fun toString(): String = "ApiKey(key=<redacted>, header=$header, prefix=$prefix)"
    }

    /**
     * FORWARD MODE: the head holds no credential of its own — the CALLER's own auth headers ride
     * to the upstream untouched (campaign claude-head: a claude head serves Claude Code against
     * api.anthropic.com on the client's native login, so splice never reads, stores or refreshes
     * an Anthropic credential).
     *
     * Deliberately a SEALED VARIANT rather than a sentinel string or a null: every consumer of
     * [Credentials] matches exhaustively, so adding it turned each auth-writing site into a
     * compile error that had to be answered — which is exactly how a "forward mode" must land.
     * It carries no secret because there is none to carry.
     */
    public data object ClientForwarded : Credentials()
}

/** Masked, wire-safe view of an auth state (never a secret). [verdict] is what upstream last said
 *  about a credential splice forwards and does not hold (V4-220 item 6b); a held one is [CredentialVerdict.Held]. */
public data class AuthDescription(
    val present: Boolean,
    val kind: String,
    val fields: Map<String, String> = emptyMap(),
    val verdict: CredentialVerdict = CredentialVerdict.Held,
)

/** The key an account's refusal sentence rides under in [AuthDescription.fields] (V4-410: a credential splice
 *  will not load). The head's account pools write it, /api/accounts and the switch route (V4-423) read it: one
 *  spelling for every end, because a rename on one side would silently take the sentence off the console. */
public const val REFUSAL_FIELD: String = "refusal"

/** Provider auth SPI: resolve credentials (cached) + masked introspection. */
public interface AuthProvider {
    public suspend fun credentials(): Credentials?

    /** What a login lookup answers: the credential to send, none, or a provider's refusal to grant one, with its
     *  cause. A provider whose lookup can refuse says why here (Muse's key mint); every other one answers from
     *  [credentials]. */
    public suspend fun answer(): LoginAnswer = credentials()?.let(LoginAnswer::Granted) ?: LoginAnswer.Missing

    /** Whose login this provider sends: splice's own, which splice renews, or the client's, which only the client can
     *  fix ([ClientAuthProvider]). A failure reader names a rejected login's owner from it. */
    public val owner: LoginOwner get() = LoginOwner.SPLICE

    public suspend fun describe(): AuthDescription

    /** Existing credential-reading join key, resolved without refresh, writes or provider requests. Never rendered. */
    public fun observedCredentialKey(): String? = null

    /** The [AccountRef] of the account the credential names now, read without refresh, writes or provider requests;
     *  null where the vendor proves no account id. Unlike [observedCredentialKey] it stays put across token refreshes,
     *  so it can name whose quota reading a file holds. */
    public fun observedAccountRef(): String? = null
}

/** An AuthProvider that can refresh its credentials (single-flight at the impl). */
public interface RefreshableAuthProvider : AuthProvider {
    public suspend fun refresh(): Credentials?

    /**
     * One token exchange, read as the turn's retry engine reads it (failure-handling slice 4): renewed, refused, or
     * unavailable ([RefreshOutcome.Unavailable] or [RefreshOutcome.TransportFailed]), which the engine asks again 2 s
     * and then 4 s later. A provider that cannot tell one failure from another answers from [refresh].
     */
    public suspend fun exchange(): RefreshOutcome =
        refresh()?.let(RefreshOutcome::Refreshed) ?: RefreshOutcome.Rejected("not renewed")

    /**
     * Provider-specific veto on spending a refresh: the reply was read as a rejected login, or as one this provider
     * names dead ([deadCredential], a ProviderDeviation's answer), and this provider says whether a refresh can
     * change it. The reading belongs to the provider's reader; this answers only whether to spend a token exchange
     * on it (failure-handling slice 3: no provider reads a status or a body here).
     */
    public fun allowRefreshAfterFailure(deadCredential: Boolean): Boolean = true

    /**
     * V4-220 item 6b: upstream answered one attempt with [status], [success] by the transport's own
     * rule. Called for every attempt, before any retry decision. A provider that holds its credential
     * learns nothing from it; one that forwards the caller's (ClientAuthProvider) learns the verdict.
     */
    public fun upstreamAnswered(status: Int, success: Boolean): Unit = Unit
}
