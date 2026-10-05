// NEW: a selected account carries its quota owner through membership changes and late response headers.
package splice.head.usage

import splice.core.usage.QuotaSnapshot
import splice.upstream.credentials.AccountQuotaSource

public class TrackedAccountQuota(public val tracker: QuotaTracker) : AccountQuotaSource {
    override fun snapshot(): QuotaSnapshot? = tracker.snapshot()
}
