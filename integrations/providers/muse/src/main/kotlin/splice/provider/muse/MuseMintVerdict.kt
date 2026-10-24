// What a key mint that did not grant a key costs the account: the hold it records, the line it logs and the cache it
// clears. Split out of MuseAuthProvider so that class stays under the function ceiling and out of the god-file band.
package splice.provider.muse

import splice.core.auth.Credentials
import splice.core.util.LogSink

internal class MuseMintVerdict(
    private val holds: MuseMintHolds,
    private val oauth: MuseOAuth,
    private val store: MuseCredentialStore,
    private val log: LogSink,
) {
    /** Records the hold [attempt] earns for [snapshot]; a mint that granted nothing never yields credentials. */
    fun hold(snapshot: MuseCredentialSnapshot, attempt: MuseMintAttempt): Credentials? {
        when (attempt) {
            is MuseMintAttempt.SubscriptionRequired -> {
                val actionUrl = oauth.safeActionOrigin(attempt.actionUrl)
                holds.recordInactive(snapshot, MAX_MINT_HOLD_MS, actionUrl)
                val action = actionUrl?.let { ": $it" }.orEmpty()
                log("[muse-auth] subscription inactive; retry held for 60 minutes$action")
            }
            is MuseMintAttempt.RateLimited -> {
                val holdMs = (attempt.retryAfterMs ?: DEFAULT_RATE_HOLD_MS)
                    .coerceIn(DEFAULT_RATE_HOLD_MS, MAX_MINT_HOLD_MS)
                holds.recordRetry(snapshot, holdMs)
                log("[muse-auth] key mint rate limited; retry held")
            }
            is MuseMintAttempt.Denied -> {
                holds.recordRetry(snapshot, MAX_MINT_HOLD_MS)
                log("[muse-auth] key mint denied (${masked(attempt.detail, snapshot)}); retry held for 60 minutes")
            }
            is MuseMintAttempt.Granted, MuseMintAttempt.InvalidAccountToken -> Unit
        }
        store.clearCache()
        return null
    }

    /** [detail] with the credentials this provider holds masked. Every denial's text is splice's own
     *  (MuseOAuth.parseMuseKeyResponse, MuseRefresh's HTTP status), and this keeps one that quotes the
     *  exchange from carrying the account token or the key into the log (V4-292). */
    private fun masked(detail: String, snapshot: MuseCredentialSnapshot): String =
        listOfNotNull(snapshot.accessToken, snapshot.apiKey).filter(String::isNotBlank)
            .fold(detail) { text, secret -> text.replace(secret, "<redacted>") }
}
