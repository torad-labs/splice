// NEW: a session whose prompt cache may still be warm keeps its login through a short rate limit (Marcos, Oct 19).
package splice.upstream.credentials

/** How long after its last turn a session's prompt cache may still be warm. Claude Code's subscriber requests ask for a
 *  one-hour cache, so a session served within the hour counts as warm; past it, a move costs no cache. */
internal const val WARM_CACHE_MS: Long = 3_600_000L

/** The longest a warm session waits out rate limits on its own login before it moves (Marcos, Oct 19: "switching mid
 *  session when the cache is hot can cost a lot"). A move rebuilds the whole conversation's cache on the next login,
 *  which costs far more than two minutes of waiting. Moves on a plan limit or a sign-in never wait. */
public const val WARM_LOGIN_WAIT_MS: Long = 120_000L

/** Whether a session stays on its login rather than move: it was served there within [WARM_CACHE_MS], and the login
 *  only needs a short wait before it serves again. */
internal object WarmSession {
    fun warm(servedAt: Long?, at: Long): Boolean = servedAt != null && at - servedAt in 0 until WARM_CACHE_MS

    /** The login serves again once a short wait ends: signed in, no plan window named spent and no full reading, and
     *  its rate-limit hold ends within [WARM_LOGIN_WAIT_MS]. A login the provider marked unavailable is no short wait.
     *  The reset a 429 body names is left out: it is often the window's reset stamped on a burst limit that clears in
     *  seconds (RateLimitCooldown, V4-61). */
    fun outwaits(account: PoolAccount, at: Long): Boolean {
        val cooldown = account.cooldown
        val signedIn = account.credentialStatus(at).selectable && cooldown.unavailableForMs() <= 0L
        return signedIn && AccountAvailability.planRoom(account, at) && cooldown.remainingMs() <= WARM_LOGIN_WAIT_MS
    }
}
