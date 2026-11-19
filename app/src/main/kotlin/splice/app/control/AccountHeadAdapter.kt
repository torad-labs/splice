// NEW: LAYOUT-01 — control-owned adapters from ManagedHead into the narrow accounts contract, so the
// accounts feature serves its routes without importing ManagedHead or any other control-plane record.
//
// The restart a head's accounts get is two restarts ([HeadLoginRestart]): the head's own, which reads each login it
// holds again, and the daemon's, the only one that builds a head with a login it did not have. The daemon's is the
// one a provider's save takes (AddMount), handed here so a sign-in takes the same decision and compaction wait.
package splice.app.control

import io.ktor.server.application.ApplicationCall
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.HeadAccountAnswerSource
import splice.accounts.HeadQuotaSource
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginRestart
import splice.app.control.api.HeadResolver
import splice.configuration.add.AddDaemonRestart
import splice.configuration.add.AddRestartTaken

/** [daemon] is the daemon's own restart, or null where there is none to take (a test's route, a probe). */
internal class AccountHeadAdapter(private val daemon: AddDaemonRestart? = null) {
    fun adapt(heads: Map<String, ManagedHead>): Map<String, AccountHead> =
        heads.mapValues { (_, head) -> adapt(head) }

    fun adapt(head: ManagedHead): AccountHead = AccountHead(
        key = head.head.key,
        auth = head.auth,
        pool = head.authSurface.accountPool,
        accountAuth = head.authSurface.accountAuth,
        restart = HeadLoginRestart(head, daemon),
        // Same fallback UsagePayloads.usageJson() reads for /api/usage's quota: the pool's selected
        // account when this head has a pool, else the head's own tracked snapshot.
        quota = HeadQuotaSource {
            val pool = head.authSurface.accountPool?.view(null)
            val tracked = head.sources.usage.snapshot().quota
            if (pool == null) tracked else pool.quotaOr(tracked)
        },
        answers = HeadAccountAnswerSource { account -> head.head.providerAnswer(account) },
    )

    /** The shared by-name lookup (key first, then wrapper command; 404/409 answered by the resolver). */
    fun resolver(resolver: HeadResolver): AccountHeadResolver = object : AccountHeadResolver {
        override suspend fun resolveOrRespond(call: ApplicationCall, name: String): AccountHead? =
            resolver.resolveHeadOrRespond(call, name)?.let(::adapt)
    }
}

/** What restarts for [head]'s accounts. The head reads which logins it has once, when the daemon builds it, so its
 *  own restart serves the ones in its pool and a login outside it needs the daemon's. A head with one login has no
 *  pool, and holds no labelled login at all. */
private class HeadLoginRestart(
    private val head: ManagedHead,
    private val daemon: AddDaemonRestart?,
) : HeadRestart {
    override suspend fun restart() {
        head.head.restart()
    }

    override fun holds(label: String): Boolean =
        head.authSurface.accountPool?.view(null)?.accounts.orEmpty().any { it.label == label }

    override fun daemonRestart(): LoginRestart {
        val restart = daemon ?: return LoginRestart.Refused("this splice has no restart of its own wired here")
        return when (val taken = restart.take()) {
            AddRestartTaken.Draining -> LoginRestart.Ready
            is AddRestartTaken.Waiting -> LoginRestart.Waiting
            is AddRestartTaken.Refused -> LoginRestart.Refused(taken.reason)
        }
    }

    override fun beginDaemonRestart() {
        daemon?.drain()
    }
}
