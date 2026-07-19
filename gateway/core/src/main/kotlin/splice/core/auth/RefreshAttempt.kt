// NEW: (G15 part A — the minimal slice of the deferred "L3 phase 2" SPI widening this gap needs)
// the classification signal that used to die at the :app layer. grokRefresh()/codexRefresh()/
// kimiRefresh() already distinguish a CONFIRMED invalid_grant (401/403/explicit invalid_grant
// body) from every other non-retryable rejection (missing access_token, unrecognized status,
// retries exhausted) via RefreshRetry.kt's isTerminalRefreshFailure — but that distinction died at
// the `T?` return type, so providers' exchangeRefreshToken() could never tell "the token is
// definitely dead" from "something else went wrong." RefreshAttempt carries the distinction across
// the :app -> :provider-* boundary. Transport exceptions are NOT modeled here — they still throw
// and are caught by exchangeRefreshToken()'s existing runCatchingCancellable { refreshCall(...) }.
package splice.core.auth

/** One provider refresh POST's classified verdict — the signal RefreshOutcome.Rejected needs to
 *  tell a confirmed-dead token (InvalidGrant) from any other non-retryable rejection (Denied). */
public sealed class RefreshAttempt<out T> {
    /** The endpoint granted rotated tokens. */
    public data class Granted<T>(val tokens: T) : RefreshAttempt<T>()

    /** 401/403, or an explicit `error":"invalid_grant"` body — the refresh token is confirmed dead. */
    public data class InvalidGrant(val detail: String) : RefreshAttempt<Nothing>()

    /** Any other non-retryable rejection: missing access_token, an unrecognized status, or the
     *  shared retry loop's attempts exhausted. NOT evidence the refresh token itself is dead. */
    public data class Denied(val detail: String) : RefreshAttempt<Nothing>()
}
