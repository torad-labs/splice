// NEW: LAYOUT-01 — the head facts the accounts routes consume. The control plane adapts its wider
// ManagedHead into this projection, so the accounts feature never depends upward on the control plane.
package splice.accounts

import io.ktor.server.application.ApplicationCall
import splice.accounts.pool.HeadAccountAuthSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthProvider

/** One head as the auth and accounts surfaces see it. */
public data class AccountHead(
    /** The topology's key for this head — the name every payload is keyed by. */
    val key: String,
    val auth: AuthProvider,
    /** Head-local OAuth account selections and quotas, projected without credential material. */
    val pool: HeadAccountPoolSource? = null,
    /** Per-account masked descriptions; never passed to the unauthenticated statusline. */
    val accountAuth: HeadAccountAuthSource? = null,
    /** Restarts this head once a login's credential has landed (the sign-in route's contract). */
    val restart: HeadRestart,
)

/** One head for a by-name /api route, or null after the resolver has answered the error itself —
 *  the key-then-command lookup, its 404 and its 409 stay the control plane's, so every capability
 *  resolves a name the same way. */
public fun interface AccountHeadResolver {
    public suspend fun resolveOrRespond(call: ApplicationCall, name: String): AccountHead?
}
