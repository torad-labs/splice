// NEW: LAYOUT-01 — control-owned adapters from ManagedHead into the narrow accounts contract, so the
// accounts feature serves its routes without importing ManagedHead or any other control-plane record.
package splice.control

import io.ktor.server.application.ApplicationCall
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.signin.HeadRestart
import splice.control.api.HeadResolver

internal object AccountHeadAdapter {
    fun adapt(heads: Map<String, ManagedHead>): Map<String, AccountHead> =
        heads.mapValues { (_, head) -> adapt(head) }

    fun adapt(head: ManagedHead): AccountHead = AccountHead(
        key = head.head.key,
        auth = head.auth,
        pool = head.accountPool,
        accountAuth = head.accountAuth,
        restart = HeadRestart { head.head.restart() },
    )

    /** The shared by-name lookup (key first, then wrapper command; 404/409 answered by the resolver). */
    fun resolver(resolver: HeadResolver): AccountHeadResolver = object : AccountHeadResolver {
        override suspend fun resolveOrRespond(call: ApplicationCall, name: String): AccountHead? =
            resolver.resolveHeadOrRespond(call, name)?.let(::adapt)
    }
}
