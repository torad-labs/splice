// NEW: Muse mint backoff and inactive-subscription verdicts keyed to credential revisions.
package splice.provider.muse

import splice.core.auth.CredentialFileIdentity
import splice.core.failure.FailureCause
import splice.core.util.WallClock

internal data class MuseMintHold(
    val identity: CredentialFileIdentity?,
    val accessToken: String?,
    val untilMs: Long,
) {
    /** The hold is KEYED to a token, so it carries one. The identity and the deadline are what make
     *  a backoff readable and neither is a secret. */
    override fun toString(): String =
        "MuseMintHold(identity=$identity, accessToken=${if (accessToken == null) "null" else "<redacted>"}, " +
            "untilMs=$untilMs)"
}

internal data class MuseInactiveVerdict(
    val identity: CredentialFileIdentity?,
    val accessToken: String?,
    val actionUrl: String?,
) {
    /** actionUrl stays readable deliberately: it is the page an operator has to visit to fix an
     *  inactive subscription, and hiding it would cost the whole point of the verdict. */
    override fun toString(): String =
        "MuseInactiveVerdict(identity=$identity, " +
            "accessToken=${if (accessToken == null) "null" else "<redacted>"}, actionUrl=$actionUrl)"
}

/** What a mint verdict refuses this account's login with, keyed to the credential revision it was recorded for. */
internal data class MuseLoginRefusal(
    val identity: CredentialFileIdentity?,
    val accessToken: String?,
    val cause: FailureCause.Provider,
) {
    /** The cause carries the provider's own words at most, never a secret: the token is the key and stays hidden. */
    override fun toString(): String =
        "MuseLoginRefusal(identity=$identity, accessToken=${if (accessToken == null) "null" else "<redacted>"}, " +
            "cause=$cause)"
}

internal class MuseMintHolds(private val clock: WallClock) {
    @Volatile
    private var hold: MuseMintHold? = null

    @Volatile
    private var inactive: MuseInactiveVerdict? = null

    /** Slice 3: the cause the last mint verdict refuses this account's login with, kept beside the hold it earned so
     *  a login lookup can answer why (LoginAnswer.Refused) instead of answering nothing. */
    @Volatile
    private var refusal: MuseLoginRefusal? = null

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

    /** The cause a login lookup is refused with now, or null when no verdict of this account's holds the login:
     *  either its subscription is inactive, which blocks the credential outright, or its retry hold still stands. */
    fun refusal(snapshot: MuseCredentialSnapshot): FailureCause.Provider? = refusal
        ?.takeIf { revisionMatches(it.identity, it.accessToken, snapshot) }
        ?.takeIf { blocksCredentials(snapshot) || suppresses(snapshot) }
        ?.cause

    fun recordRefusal(snapshot: MuseCredentialSnapshot, cause: FailureCause.Provider) {
        refusal = MuseLoginRefusal(snapshot.identity, snapshot.accessToken, cause)
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
        refusal = null
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
