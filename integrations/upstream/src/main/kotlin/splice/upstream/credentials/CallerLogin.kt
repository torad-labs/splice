// NEW: the login a caller presented and what the pool decides from it, moved verbatim from AccountPool.kt as top-level
// internal types (they were private nested classes there). Same package: no call site changes.
package splice.upstream.credentials

import splice.core.util.WallClock

/** The login the caller presented, and what it decides for one selection: which account it asks for, whether the
 *  session carries its history into the choice, and which login the session remembers afterwards. A call without
 *  a session has no history to carry and nothing to remember it by. */
internal class CallerLogin(key: String?, val session: String?, accounts: List<PoolAccount>) {
    private val mark: CallerMark? = key?.let { presented ->
        accounts.singleOrNull { it.auth.observedCredentialKey() == presented }?.let {
            CallerMark(it.label, (it.auth as? AccountCredentialIdentitySource)?.credentialAccount())
        }
    }

    /** The account this login names, unless the session already acted on this same login: a changed login takes
     *  precedence over automatic stickiness, an unchanged one has had its turn. Logins are compared by account,
     *  never by token: every token refresh changed the key, and pulled a warm session back to its own sign-in as
     *  if the person had signed in again (Oct 19). */
    fun preferred(previous: SessionAccount?): String? =
        mark?.takeIf { session == null || it.differs(previous?.caller) }?.label

    /** The label the choice starts from: the session's last account, never a stateless call's. */
    fun continuesFrom(previous: SessionAccount?): String? = previous?.label.takeIf { session != null }

    /** The login the session records: this one when it names an account, else the one it already had. */
    fun remembered(previous: SessionAccount?): CallerMark? = mark?.keeping(previous?.caller) ?: previous?.caller

    /** The login a warm session keeps through a short rate limit, or null: no session, a cold one, a pin or a
     *  changed caller login elsewhere ([preference]), or a login that needs more than a short wait. */
    fun stays(
        previous: SessionAccount?,
        byLabel: Map<String, PoolAccount>,
        preference: List<PoolAccount>,
        at: Long,
    ): PoolAccount? = previous?.label?.takeIf { session != null }?.let(byLabel::get)
        ?.let { AccountAvailability.Warm.stays(it, stampOf(previous)?.at, preference, at) }

    /** [stays] as an operator surface previews it: no caller login is presented there, so only a pin can pull the
     *  session elsewhere. */
    fun previewStay(
        previous: SessionAccount?,
        byLabel: Map<String, PoolAccount>,
        pinLabel: String?,
        at: Long,
    ): PoolAccount? = stays(previous, byLabel, listOfNotNull(pinLabel?.let(byLabel::get)), at)

    /** Whether this turn stays where the session's prompt cache may be warm: a session that did not move, last
     *  served within the hour on the account it is on. */
    fun keepsCache(previous: SessionAccount?, moved: AccountSwitch?, at: Long): Boolean =
        session != null && moved == null && AccountAvailability.Warm.recent(stampOf(previous)?.at, at)

    /** The stamp this selection carries: the session's own while it stays on the same login and account, a new
     *  one, which nothing has served yet, once it moves. */
    fun stampFor(previous: SessionAccount?, moved: AccountSwitch?, now: WallClock): ServedStamp =
        stampOf(previous)?.takeIf { moved == null } ?: ServedStamp(now)

    /** The session's served stamp, or null once a different provider account has signed in at that place: the
     *  prompt cache lived with the account that is gone, so neither its hour nor its stamp carry over. */
    private fun stampOf(previous: SessionAccount?): ServedStamp? =
        previous?.served?.takeUnless { mark?.replaces(previous.caller) == true }

    /** Why a move went to the caller's own login: the person signed in to another account. Null for any other
     *  move, and for a pin, which names itself. */
    fun movedBy(previous: SessionAccount?, chosen: PoolAccount, pinLabel: String?): String? =
        AccountSwitchReason.CALLER_LOGIN.takeIf { chosen.label != pinLabel && chosen.label == preferred(previous) }
}

/** A caller's login: the pool account its token matches, and the provider account behind it where one is proved.
 *  A token refresh keeps the account; a new sign-in in the same place changes it (ClaudeNativeLoginResumeTest). */
internal data class CallerMark(val label: String, val account: String?) {
    fun differs(prior: CallerMark?): Boolean = prior == null || prior.label != label || replaces(prior)

    /** A different provider account now signs in at the place [prior] named: the same pool account, both
     *  accounts proved, and they are not the same. */
    fun replaces(prior: CallerMark?): Boolean =
        prior != null && prior.label == label && account != null && prior.account != null &&
            account != prior.account

    /** A token whose account is not proved yet (a refresh not yet read) keeps the account the login had. */
    fun keeping(prior: CallerMark?): CallerMark = prior?.takeIf { account == null && it.label == label } ?: this
}

/** [served] says when this login last served the session cleanly, so whether its prompt cache may be warm. */
internal data class SessionAccount(
    val label: String,
    val lastSwitch: AccountSwitch?,
    val caller: CallerMark? = null,
    val served: ServedStamp? = null,
)
