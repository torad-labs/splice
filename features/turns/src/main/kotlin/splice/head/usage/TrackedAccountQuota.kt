// NEW: a selected account carries its quota owner through membership changes and late response headers.
package splice.head.usage

import splice.core.usage.QuotaSnapshot
import splice.upstream.credentials.AccountQuotaSource

public class TrackedAccountQuota(
    /** Internal on purpose: TurnQuota alone resolves which tracker a turn reads, so nothing outside this module can
     *  take an account's tracker and rebuild the precedence by hand. */
    internal val tracker: QuotaTracker,
    private val read: AccountQuotaSource? = null,
) : AccountQuotaSource {
    override val held: Boolean get() = read?.held == true

    override fun snapshot(): QuotaSnapshot? = if (read == null) tracker.snapshot() else read.snapshot()
}
