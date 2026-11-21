// NEW: gateway-no-heads. What one persisted identity accounts into, held once however many routes serve it: a legacy
// head and the gateway's provider runtime are two routes over one provider's books when they are really the same
// provider, and each owns a route of its own (its catalog, its prompts, its pricing) while the stores, the account
// pool and the quota pollers are the books'. Two routes with a store each over one file would serve each other stale
// copies and write each other's hours over, so the books are built once per key and asked for by key.
package splice.app.head

import splice.app.provider.Wired
import splice.head.ProviderRuntime
import splice.head.compaction.FileCompactionRecordings
import splice.head.usage.QuotaTracker
import splice.usage.quota.QuotaPoller

/** One identity's books: [stores] (usage, rate limits, perf rows, economics, the account pool and its quotas, client
 *  windows, holds), [quota] (what fills them) and the kept compaction answers ([recordings]). [provider] is the
 *  provider these books belong to: a key asked for under another provider is another identity, never these books. */
internal data class ProviderBooks(
    val provider: String,
    val stores: HeadStores,
    val quota: BooksQuota,
    val recordings: FileCompactionRecordings,
)

/** The quota half of a provider's books: the primary tracker and one per account, the pollers that fill them and the
 *  polling that started them (which also answers when a limit resets). */
internal data class BooksQuota(
    val primary: QuotaTracker,
    val accounts: Map<String, QuotaTracker>,
    val pollers: List<QuotaPoller>,
    val polling: HeadQuotaPolling,
)

/** One provider as the gateway serves it: [wired] (the provider and its credential), the [books] it accounts into, and
 *  the [runtime] a gateway turn runs through. */
internal data class AssembledRoute(
    val wired: Wired,
    val books: ProviderBooks,
    val runtime: ProviderRuntime,
)
