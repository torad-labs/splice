package splice.app.head

import splice.head.usage.QuotaTracker
import splice.upstream.credentials.AccountPool

/** The OAuth account pool a head draws from and the quota tracker kept for each of its accounts. */
internal data class HeadAccountStores(
    val pool: AccountPool? = null,
    val quotas: Map<String, QuotaTracker> = emptyMap(),
)
