// NEW: (discipline L3, 2026-07-18) the sealed outcome of a token refresh. The incident class this
// kills: every provider's doRefresh() collapsed all six distinct failure modes into one `null` —
// a dead refresh token, a DNS blip, a corrupt auth file, and "not logged in" were literally the
// same value, so the operator saw identical symptoms for problems with opposite fixes. A sealed
// type makes the modes distinct and the single null-collapse boundary (credentialsOrNull) is the
// ONE place they flatten for the SPI — after each branch has logged its own story. Widening the
// SPI itself to return RefreshOutcome is planned with the shared refresh helper (G7 in
// dev/research/gateway-gaps-tracker.md).
package splice.core.auth

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
}

/**
 * The SINGLE sanctioned flatten to the `RefreshableAuthProvider.refresh(): Credentials?` SPI shape.
 * Exhaustive by construction — a new outcome branch fails compilation here, not silently at
 * runtime — and every non-success branch logs its own distinguishable line before nulling.
 */
public fun RefreshOutcome.credentialsOrNull(
    tag: String,
    log: (String) -> Unit = System.err::println,
): Credentials? = when (this) {
    is RefreshOutcome.Refreshed -> credentials
    RefreshOutcome.NoCredentialsFile -> {
        log("[$tag] refresh skipped: no credential file — not logged in")
        null
    }
    RefreshOutcome.NoRefreshToken -> {
        log("[$tag] refresh skipped: no refresh token on file — re-login required")
        null
    }
    is RefreshOutcome.Rejected -> {
        log("[$tag] refresh rejected by token endpoint: $reason")
        null
    }
    is RefreshOutcome.ReadFailed -> {
        log("[$tag] credential file read failed (NOT a logged-out state): $cause")
        null
    }
    is RefreshOutcome.TransportFailed -> {
        log("[$tag] refresh transport failed (likely transient): $cause")
        null
    }
    is RefreshOutcome.PersistFailed -> {
        log("[$tag] refresh rotated upstream but local persist failed: $reason — old token may be dead, re-login if errors persist")
        null
    }
}
