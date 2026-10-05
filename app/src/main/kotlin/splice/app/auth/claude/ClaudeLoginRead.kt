// NEW: each place joins only its currently live credential to successful windows and provider refusals.
//
// WINDOWS AND HOLDS JOIN ONLY PROVIDER-VERIFIED ACCOUNTS: a reading is filed under its observing credential.
// A place may read another live credential's newest window only when both provider profiles prove the same account.
// Copied client settings never prove that join. Two rules hold it in:
//   - only a LIVE credential counts, so a rotated token inherits nothing from the token it replaced, and a window
//     whose observer is gone from every place is gone from the join;
//   - an account splice cannot identify joins with nobody and keeps its own token's reading, because without an
//     identity there is nothing to join by and a guess would put one person's window on another's card.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginManagement
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginStanding
import splice.app.head.ProviderHoldFiles
import splice.core.config.StatePaths
import splice.core.usage.QuotaSnapshot
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.usage.CredentialQuotaFiles

/** Resolves a saved display name only against the same credential snapshot that supplied identity and windows. */
internal fun interface ClaudeLoginNames {
    fun name(place: ClaudeLoginPlaceId, key: String): String?
}

internal class ClaudeLoginRead(
    private val paths: StatePaths,
    private val log: LogSink,
    private val clock: WallClock,
    private val profileRefresh: ClaudeIdentityRefresh? = null,
) {
    private val facts = ClaudeLoginFactsReader(ClaudeCredentialProfiles(paths.stateDir, log), profileRefresh)
    private val holds = ProviderHoldFiles(paths, log)

    /** Every place's view. The whole set is read at once because the window join is a property of the SET: one
     *  account's reading is the newest filed under any of its live credentials, in whichever place it signed in. */
    fun places(
        locations: List<ClaudeLoginLocation>,
        names: ClaudeLoginNames? = null,
    ): List<ClaudeLoginPlaceView> {
        val read = locations.map { it to facts.read(it) }
        val newest = read.mapNotNull { (location, native) -> filed(location, native) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, windows) -> windows.maxBy(QuotaSnapshot::updatedAt) }
        val held = read.mapNotNull { (location, native) ->
            val account = native.account?.uuid ?: return@mapNotNull null
            holdUntil(location, native)?.let { account to it }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, resets) -> resets.max() }
        return read.map { (location, native) ->
            val account = native.account?.uuid
            view(location, native, account?.let(newest::get), account?.let(held::get), names)
        }
    }

    /** Internal saved-label proof only. The private digest never enters a view or a route payload. */
    fun credentialKey(location: ClaudeLoginLocation): String? = facts.read(location).key

    /** Product refreshes wait only for the selected place's captured credential. Other places retain their facts. */
    suspend fun refresh(location: ClaudeLoginLocation) = facts.refresh(location.target.head.configDir)

    /** The account proved by the credential used for this request, not by its command or login place. */
    fun accountForCredential(locations: List<ClaudeLoginLocation>, key: String): ClaudeAccountIdentity? =
        locations.map(facts::read)
            .filter { it.key == key && it.refusal == null }
            .mapNotNull(ClaudeLoginFacts::account)
            .distinctBy(ClaudeAccountIdentity::uuid)
            .singleOrNull()

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
        joinedUntil: Long?,
        names: ClaudeLoginNames?,
    ): ClaudeLoginPlaceView {
        val head = location.target.head.key
        val quota = joined ?: native.key?.takeIf { native.account == null }?.let { quota(location, it) }
        val until = if (native.account == null) holdUntil(location, native) else joinedUntil
        val name = native.key?.let { names?.name(location.id, it) }
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
            management = names?.let {
                ClaudeLoginManagement(name ?: location.id.command, native.present, name != null)
            },
        )
    }

    /** Only a live token can lend its provider's hold to the account it proves, just as with windows. */
    private fun holdUntil(location: ClaudeLoginLocation, native: ClaudeLoginFacts): Long? {
        val hold = native.key?.let { holds.forHead(location.target.head.key).forCredential(it)?.load() }
        return listOfNotNull(hold?.providerResetAtEpochSeconds, hold?.plan?.resetEpochSeconds).maxOrNull()
    }

    private fun quota(location: ClaudeLoginLocation, key: String): QuotaSnapshot? =
        CredentialQuotaFiles(paths.quotaFile(location.target.head.key), log).read(key)
}
