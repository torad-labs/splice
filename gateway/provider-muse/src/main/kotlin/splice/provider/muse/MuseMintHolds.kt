// NEW: Muse mint backoff and inactive-subscription verdicts keyed to credential revisions.
package splice.provider.muse

import splice.core.auth.CredentialFileIdentity
import splice.core.util.WallClock

internal data class MuseMintHold(
    val identity: CredentialFileIdentity?,
    val accessToken: String?,
    val untilMs: Long,
)

internal data class MuseInactiveVerdict(
    val identity: CredentialFileIdentity?,
    val accessToken: String?,
    val actionUrl: String?,
)

internal class MuseMintHolds(private val clock: WallClock) {
    @Volatile
    private var hold: MuseMintHold? = null

    @Volatile
    private var inactive: MuseInactiveVerdict? = null

    fun suppresses(snapshot: MuseCredentialSnapshot): Boolean {
        val current = hold ?: return false
        return revisionMatches(current.identity, current.accessToken, snapshot) && clock() < current.untilMs
    }

    fun blocksCredentials(snapshot: MuseCredentialSnapshot): Boolean {
        val current = inactive ?: return false
        return revisionMatches(current.identity, current.accessToken, snapshot)
    }

    fun description(snapshot: MuseCredentialSnapshot): Map<String, String> {
        val verdict = inactive?.takeIf {
            revisionMatches(it.identity, it.accessToken, snapshot)
        } ?: return emptyMap()
        return buildMap {
            put("subscription", "inactive")
            verdict.actionUrl?.let { put("action_url", it) }
        }
    }

    fun recordRetry(snapshot: MuseCredentialSnapshot, holdMs: Long) {
        hold = MuseMintHold(snapshot.identity, snapshot.accessToken, clock() + holdMs)
    }

    fun recordInactive(snapshot: MuseCredentialSnapshot, holdMs: Long, actionUrl: String?) {
        inactive = MuseInactiveVerdict(snapshot.identity, snapshot.accessToken, actionUrl)
        recordRetry(snapshot, holdMs)
    }

    fun clear() {
        hold = null
        inactive = null
    }

    private fun revisionMatches(
        identity: CredentialFileIdentity?,
        accessToken: String?,
        snapshot: MuseCredentialSnapshot,
    ): Boolean = if (identity != null && snapshot.identity != null) {
        identity == snapshot.identity
    } else {
        accessToken == snapshot.accessToken
    }
}
