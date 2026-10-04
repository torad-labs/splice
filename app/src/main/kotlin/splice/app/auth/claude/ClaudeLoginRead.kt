// NEW: each place joins only its currently live credential to successful windows and provider refusals.
//
// WINDOWS ARE JOINED BY ACCOUNT, NOT BY TOKEN (2026-10-04): Anthropic's 5-hour and 7-day windows belong to the
// SUBSCRIPTION, so every credential of one account is spending the same window. A reading is FILED under the token
// that observed it, and joining it back by that token split one account's window across its tokens: on this machine
// both Claude places hold one account under two different tokens, and Accounts drew the same email twice, one card
// reading 34% and 34% and the other reading "Not reported" (marlin's second pass, Oct 3).
//
// So a place reads the newest window filed under ANY of its account's live credentials. Two rules hold it in:
//   - only a LIVE credential counts, so a rotated token inherits nothing from the token it replaced, and a window
//     whose observer is gone from every place is gone from the join;
//   - an account splice cannot identify joins with nobody and keeps its own token's reading, because without an
//     identity there is nothing to join by and a guess would put one person's window on another's card.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginStanding
import splice.app.head.ProviderHoldFiles
import splice.core.config.StatePaths
import splice.core.usage.QuotaSnapshot
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.usage.CredentialQuotaFiles

internal class ClaudeLoginRead(
    private val paths: StatePaths,
    private val log: LogSink,
    private val clock: WallClock,
) {
    private val facts = ClaudeLoginFactsReader()
    private val holds = ProviderHoldFiles(paths, log)

    /** Every place's view. The whole set is read at once because the window join is a property of the SET: one
     *  account's reading is the newest filed under any of its live credentials, in whichever place it signed in. */
    fun places(locations: List<ClaudeLoginLocation>): List<ClaudeLoginPlaceView> {
        val read = locations.map { it to facts.read(it) }
        val newest = read.mapNotNull { (location, native) -> filed(location, native) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, windows) -> windows.maxBy(QuotaSnapshot::updatedAt) }
        return read.map { (location, native) ->
            view(location, native, native.account?.uuid?.let(newest::get))
        }
    }

    /** What this place's live credential has observed, under the account spending it, or null for either absence. */
    private fun filed(location: ClaudeLoginLocation, native: ClaudeLoginFacts): Pair<String, QuotaSnapshot>? {
        val account = native.account?.uuid ?: return null
        val window = native.key?.let { quota(location, it) } ?: return null
        return account to window
    }

    private fun view(
        location: ClaudeLoginLocation,
        native: ClaudeLoginFacts,
        joined: QuotaSnapshot?,
    ): ClaudeLoginPlaceView {
        val head = location.target.head.key
        val quota = joined ?: native.key?.takeIf { native.account == null }?.let { quota(location, it) }
        val hold = native.key?.let { holds.forHead(head).forCredential(it)?.load() }
        val until = listOfNotNull(hold?.providerResetAtEpochSeconds, hold?.plan?.resetEpochSeconds).maxOrNull()
        return ClaudeLoginPlaceView(
            id = location.id,
            head = head,
            credentialPath = location.credentials.toString(),
            credentialPresent = native.present,
            account = native.account,
            quota = quota,
            standing = ClaudeLoginStanding(
                held = until?.let { it > clock() / 1000L },
                untilEpochSeconds = until,
            ),
            refusal = native.refusal,
        )
    }

    private fun quota(location: ClaudeLoginLocation, key: String): QuotaSnapshot? =
        CredentialQuotaFiles(paths.quotaFile(location.target.head.key), log).read(key)
}
