// NEW: V4-444 — the provider each assembled head serves with, by head key, so the Playground builds its one call
// through the head's own provider instead of a hand-built copy of each dialect's request (the copy drifted, and
// ChatGPT refused every Playground send with 400 "Input must be a list"). ManagedHeadFactory registers a head's
// provider when it assembles it; the probe reads it at call time. A head assembled again replaces its entry.
//
// A COMMAND WITH TWO OR MORE LOGINS (failover within one provider, splice-lead's 6:06 PM CT followup) also registers
// its pool and the logins it was built from, so the Playground sends as the login a real turn would use next: the
// pool's own read-only answer (AccountPool.nextTargetLabel), never the head's default credential.
package splice.app.probe

import splice.app.provider.WiredAccount
import splice.core.auth.AuthProvider
import splice.upstream.CredentialHeaders
import splice.upstream.Provider
import splice.upstream.credentials.AccountPool
import java.util.concurrent.ConcurrentHashMap

/** The login a real turn would use next on a pooled command: its [label], its credential and its own headers. */
internal data class PlaygroundLogin(val label: String, val auth: AuthProvider, val headers: CredentialHeaders?)

internal class PlaygroundProviders {
    private val byKey = ConcurrentHashMap<String, Provider>()
    private val pools = ConcurrentHashMap<String, Pooled>()

    private data class Pooled(val pool: AccountPool, val logins: Map<String, WiredAccount>)

    fun register(key: String, provider: Provider) {
        byKey[key] = provider
    }

    /** Registers [key]'s pool and the [accounts] it was built from; a command with no pool registers none. */
    fun logins(key: String, pool: AccountPool?, accounts: List<WiredAccount>) {
        if (pool == null) pools.remove(key) else pools[key] = Pooled(pool, accounts.associateBy(WiredAccount::label))
    }

    operator fun get(key: String): Provider? = byKey[key]

    /** The login [key]'s next turn would use, or null when the command has one login (or every login is out). */
    fun login(key: String): PlaygroundLogin? {
        val pooled = pools[key] ?: return null
        val account = pooled.pool.nextTargetLabel()?.let(pooled.logins::get) ?: return null
        return PlaygroundLogin(account.label, account.auth, account.extraHeaders)
    }
}
