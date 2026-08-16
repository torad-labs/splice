// NEW: (discipline L3, 2026-07-18) the sealed outcome of a token refresh. The incident class this
// kills: every provider's doRefresh() collapsed all six distinct failure modes into one `null` —
// a dead refresh token, a DNS blip, a corrupt auth file, and "not logged in" were literally the
// same value, so the operator saw identical symptoms for problems with opposite fixes. A sealed
// type makes the modes distinct and the single null-collapse boundary (credentialsOrNull) is the
// ONE place they flatten for the SPI — after each branch has logged its own story.
// G15: INVALID_GRANT_REASON is the canonical Rejected.reason marker a confirmed invalid_grant
// classification (see RefreshAttempt.kt) produces, so InvalidGrantLatch can key its gate on it.
package splice.core.auth

import splice.core.util.DaemonLog

/** The Rejected.reason marker for a CONFIRMED invalid_grant (401/403/explicit invalid_grant body) —
 *  as opposed to any other non-retryable refresh rejection. Paired with [InvalidGrantLatch]. */
public const val INVALID_GRANT_REASON: String = "invalid_grant"

/** Everything a provider's refresh attempt can resolve to — one branch per distinct failure story. */
public sealed class RefreshOutcome {
    /** Rotated tokens persisted; these credentials are ready to serve. */
    public data class Refreshed(val credentials: Credentials) : RefreshOutcome()

    /** No credential file on disk — not logged in; refresh is impossible by construction. */
    public data object NoCredentialsFile : RefreshOutcome()

    /** Credential file exists but carries no refresh token — re-login is the only path forward. */
    public data object NoRefreshToken : RefreshOutcome()

    /** The token endpoint answered and did not grant (invalid_grant, missing rotation fields, retries exhausted). */
    public data class Rejected(val reason: String) : RefreshOutcome()

    /** Reading/parsing the credential file failed (I/O error or malformed JSON) — NOT "not logged in". */
    public data class ReadFailed(val cause: Throwable) : RefreshOutcome()

    /** The refresh network hop threw before the endpoint could answer (DNS, connect, TLS). */
    public data class TransportFailed(val cause: Throwable) : RefreshOutcome()

    /** Tokens were rotated upstream but persisting/re-reading them locally failed — urgent: the old refresh token may already be dead. */
    public data class PersistFailed(val reason: String) : RefreshOutcome()

    /**
     * The SINGLE sanctioned flatten to the `RefreshableAuthProvider.refresh(): Credentials?` SPI shape.
     * Exhaustive by construction — a new outcome branch fails compilation here, not silently at
     * runtime — and every non-success branch logs its own distinguishable line before nulling.
     *
     * A member since the 2026-08-16 style migration (HD-M8): it was a top-level extension on this
     * very sealed class, so the receiver simply moved inside and every call site is unchanged.
     */
    public fun credentialsOrNull(
        tag: String,
        // Was `= System.err::println`, which reached stderr ONLY and so never appeared in /mgmt/logs
        // (wall kt-no-println, 2026-07-27). The default now resolves to the process sink Main installs,
        // which writes daemon.log; daemon callers still pass their own injected sink explicitly, and
        // tests pass a capturing one. Uninstalled, DaemonLog is a no-op — never a silent stderr write.
        log: (String) -> Unit = DaemonLog::write,
    ): Credentials? = when (this) {
        is Refreshed -> credentials
        NoCredentialsFile -> {
            log("[$tag] refresh skipped: no credential file — not logged in")
            null
        }
        NoRefreshToken -> {
            log("[$tag] refresh skipped: no refresh token on file — re-login required")
            null
        }
        is Rejected -> {
            log("[$tag] refresh rejected by token endpoint: $reason")
            null
        }
        is ReadFailed -> {
            log("[$tag] credential file read failed (NOT a logged-out state): $cause")
            null
        }
        is TransportFailed -> {
            log("[$tag] refresh transport failed (likely transient): $cause")
            null
        }
        is PersistFailed -> {
            log(
                "[$tag] refresh rotated upstream but local persist failed: $reason — " +
                    "old token may be dead, re-login if errors persist",
            )
            null
        }
    }
}
