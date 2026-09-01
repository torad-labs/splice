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

/**
 * The one refresh POST — `grant_type=refresh_token` against the provider's token endpoint —
 * returning the classified verdict rather than a nullable token bundle.
 *
 * The seam the three OAuth providers (codex, grok, kimi) inject over their own token URL, and the
 * reason it is a seam at all: the HTTP hop lives in :app (where Ktor and the retry loop are) while
 * the credential state machine lives in :provider-*, so a provider is handed the CALL and never the
 * client. That is also what lets a provider test drive invalid-grant, denial and rotation paths with
 * no network.
 *
 * [T] is the provider's own rotated-token shape (`RefreshedTokens`, `GrokRefreshedTokens`,
 * `KimiRefreshedTokens`), which is why this is generic rather than three near-identical types: the
 * ROLE is identical at all three seams and only the payload differs. Distinct from the daemon's own
 * two-argument refresh, which additionally chooses the token URL — see `TokenUrlRefreshCall`.
 *
 * Transport exceptions are NOT modelled here: they still throw, and the provider's
 * `runCatchingCancellable { refreshCall(...) }` owns them.
 */
public fun interface RefreshCall<T> {
    public suspend operator fun invoke(refreshToken: String): RefreshAttempt<T>
}
