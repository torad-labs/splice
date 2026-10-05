// NEW: V4-444 — the provider each assembled head serves with, by head key, so the Playground builds its one call
// through the head's own provider instead of a hand-built copy of each dialect's request (the copy drifted, and
// ChatGPT refused every Playground send with 400 "Input must be a list"). ManagedHeadFactory registers a head's
// provider when it assembles it; the probe reads it at call time. A head assembled again replaces its entry.
//
// A COMMAND WITH TWO OR MORE LOGINS (failover within one provider, splice-lead's 6:06 PM CT followup) also registers
// its pool and the logins it was built from, so the Playground sends as the login a real turn would use next: the
// pool's own read-only answer (AccountPool.nextTargetLabel), never the head's default credential.
package splice.app.probe

import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.core.auth.AuthProvider
import splice.head.usage.ProviderReplyObserver
import splice.upstream.CredentialHeaders
import splice.upstream.Provider
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.PoolAccount
import java.util.concurrent.ConcurrentHashMap

/** The login a real turn would use next on a pooled command: its [label], its credential and its own headers. */
internal data class PlaygroundLogin(
    val label: String,
    val auth: AuthProvider,
    val headers: CredentialHeaders?,
    val selectedAccount: PoolAccount? = null,
)

/** One head generation, captured before credentials suspend. Raw provider-only callers supply their own auth. */
internal data class PlaygroundTarget(
    val provider: Provider,
    val auth: AuthProvider?,
    val login: PlaygroundLogin?,
    val observer: ProviderReplyObserver?,
)

internal class PlaygroundProviders {
    private val byKey = ConcurrentHashMap<String, Binding>()

    private data class Pooled(val pool: AccountPool)
    private data class Binding(
        val provider: Provider,
        val auth: AuthProvider? = null,
        val observer: ProviderReplyObserver? = null,
        val pooled: Pooled? = null,
    )

    fun register(key: String, provider: Provider) {
        byKey[key] = Binding(provider)
    }

    /** One publication owns the provider, actual auth, pool and reply receiver of an assembled head. */
    fun bind(key: String, wired: Wired, pool: AccountPool?, observer: ProviderReplyObserver) {
        byKey[key] = Binding(wired.provider, wired.auth, observer, pooled(pool, wired.accounts))
    }

    /** Standalone provider fixtures may attach their pool after registration. Production uses [bind]. */
    fun logins(key: String, pool: AccountPool?, accounts: List<WiredAccount>) {
        val current = byKey[key] ?: return
        byKey[key] = current.copy(pooled = pooled(pool, accounts))
    }

    operator fun get(key: String): Provider? = byKey[key]?.provider

    fun target(key: String): PlaygroundTarget? {
        val binding = byKey[key] ?: return null
        return PlaygroundTarget(binding.provider, binding.auth, login(binding.pooled), binding.observer)
    }

    private fun pooled(pool: AccountPool?, accounts: List<WiredAccount>): Pooled? = pool?.let {
        val labels = accounts.map(WiredAccount::label).toSet()
        require(accounts.isEmpty() || labels == it.members.map { member -> member.label }.toSet()) {
            "Playground binding and selector must start with the same accounts"
        }
        Pooled(it)
    }

    private fun login(pooled: Pooled?): PlaygroundLogin? {
        val pool = pooled?.pool?.takeIf { it.active } ?: return null
        val account = pool.nextTargetLabel()?.let { label -> pool.members.singleOrNull { it.label == label } }
            ?: return null
        return PlaygroundLogin(account.label, account.auth, account.extraHeaders, account)
    }
}
